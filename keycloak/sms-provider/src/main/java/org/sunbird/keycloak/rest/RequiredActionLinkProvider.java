package org.sunbird.keycloak.rest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.ws.rs.Consumes;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.POST;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriBuilder;
import org.jboss.logging.Logger;
import org.keycloak.authentication.actiontoken.execactions.ExecuteActionsActionToken;
import org.keycloak.common.util.Time;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.protocol.oidc.utils.RedirectUtils;
import org.keycloak.services.ErrorResponse;
import org.keycloak.services.managers.AppAuthManager;
import org.keycloak.services.managers.AuthenticationManager.AuthResult;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.services.resources.LoginActionsService;
import org.sunbird.keycloak.utils.Constants;

/**
 * 
 * @author Amit Kumar
 *
 */
public class RequiredActionLinkProvider implements RealmResourceProvider {

  private static Logger logger =
      Logger.getLogger(RequiredActionLinkProvider.class);
  private KeycloakSession session;
  private static final String UPDATE_PASSWORD = "UPDATE_PASSWORD";
  private static final String VERIFY_EMAIL = "VERIFY_EMAIL";

  public RequiredActionLinkProvider(KeycloakSession session) {
    this.session = session;
  }

  /**
   * create a link on which the user can click to reset their password or verify email based on
   * action param value.
   *
   * @param request Map<String,String> request object which contains details of redirectUri,client-id,action,userName
   * @return
   */
  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response generateRequiredActionLink(Map<String, String> request) {
    String redirectUri = request.get(Constants.REDIRECT_URI);
    String clientId = request.get(Constants.CLIENT_ID);
    String actionName = request.get(Constants.REQUIRED_ACTION);
    String userName = request.get(Constants.USERNAME);
    
    
    Integer expirationInSecs = null;
    try {
      expirationInSecs = Integer.parseInt(request.get(Constants.EXPIRATION_IN_SECS));
    } catch (Exception ex) {
      return ErrorResponse.error(Constants.INVALID_LIFESPAN_VALUE + request.get(Constants.EXPIRATION_IN_SECS), Status.BAD_REQUEST);
    }
    String authRequired = request.get(Constants.IS_AUTH_REQUIRED);
    if (null != authRequired) {
      Boolean isAuthRequired = null;
      try {
        isAuthRequired = Boolean.parseBoolean(authRequired);
      } catch (Exception ex) {
        return ErrorResponse.error(Constants.INVALID_AUTH_REQRD_VALUE + authRequired,
            Status.BAD_REQUEST);
      }
      if (isAuthRequired) {
        try {
          // Validate the Authorization key in header
          checkRealmAdmin();
        } catch (NotAuthorizedException ex) {
          return ErrorResponse.error(Constants.NOT_AUTHORIZED, Status.UNAUTHORIZED);
        } catch (ForbiddenException ex) {
          return ErrorResponse.error(Constants.DOES_NOT_HAVE_REALM_ADMIN_ROLE, Status.FORBIDDEN);
        }
      }
    }
    return executeAction(redirectUri, clientId, expirationInSecs, actionName, userName);
  }


  /**
   * create update password link
   *
   * create a link on which the user can click to perform a set of required actions. The redirectUri
   * and clientId parameters are optional. If no redirect is given, then there will be no link back
   * to click after actions have completed. Redirect uri must be a valid uri for the particular
   * clientId.
   *
   * @param redirectUri Redirect uri
   * @param clientId Client id
   * @param lifespan Number of seconds after which the generated token expires
   * @param actionName required action the user needs to complete
   * @param userName user name to generate the link for required actions
   * @return
   */
  private Response executeAction(String redirectUri, String clientId, Integer expirationInSecs,
      String actionName, String userName) {
    UserModel user = KeycloakModelUtils.findUserByNameOrEmail(session,
        session.getContext().getRealm(), userName);
    if (user == null) {
      return ErrorResponse.error(Constants.INVALID_USERNAME + userName, Status.BAD_REQUEST);
    }

    if (!user.isEnabled()) {
      throw new WebApplicationException(
          ErrorResponse.error(Constants.USER_IS_DISABLED, Status.BAD_REQUEST));
    }

    if (redirectUri != null && clientId == null) {
      throw new WebApplicationException(
          ErrorResponse.error(Constants.CLIENT_ID_MISSING, Status.BAD_REQUEST));
    }

    if (clientId == null) {
      clientId = Constants.ACCOUNT;
    }

    ClientModel client = session.getContext().getRealm().getClientByClientId(clientId);
    if (client == null || !client.isEnabled()) {
      throw new WebApplicationException(
          ErrorResponse.error(clientId + Constants.NOT_ENABLED, Status.BAD_REQUEST));
    }

    String redirect;
    if (redirectUri != null) {
      redirect = RedirectUtils.verifyRedirectUri(session.getContext().getUri(), redirectUri,
          session.getContext().getRealm(), client);
      if (redirect == null) {
        throw new WebApplicationException(
            ErrorResponse.error(Constants.INVALID_REDIRECT_URI, Status.BAD_REQUEST));
      }
    }

    if (expirationInSecs == null) {
      expirationInSecs = Constants.DEFAULT_LINK_EXPIRATION_IN_SECS;
    }
    int expiration = Time.currentTime() + expirationInSecs;
    List<String> requiredActions = getRequiredActions(actionName);
    
    ExecuteActionsActionToken token =
        new ExecuteActionsActionToken(user.getId(), expiration, requiredActions, redirectUri, clientId);

    try {
      UriBuilder builder = LoginActionsService.actionTokenProcessor(session.getContext().getUri());
      builder.queryParam(Constants.KEY,
          token.serialize(session, session.getContext().getRealm(), session.getContext().getUri()));

      String link = builder.build(session.getContext().getRealm().getName()).toString();
      Map<String, Object> response = new HashMap<>();
      response.put(Constants.LINK, link);
      return Response.ok(response).build();
    } catch (Exception e) {
      return ErrorResponse.error(Constants.FAILED_TO_CREATE_LINK, Status.INTERNAL_SERVER_ERROR);
    }
  }

  private List<String> getRequiredActions(String actionName) {
    List<String> requiredActions = new ArrayList<>();
    switch(actionName){    
      case UPDATE_PASSWORD:    
        requiredActions.add(UserModel.RequiredAction.UPDATE_PASSWORD.name());   
       break;    
      case VERIFY_EMAIL:    
        requiredActions.add(UserModel.RequiredAction.VERIFY_EMAIL.name());   
       break;   
      default: 
        throw new WebApplicationException(ErrorResponse.error(Constants.INVALID_ACTION + actionName, Status.BAD_REQUEST));
      }
    return requiredActions;
  }

  private void checkRealmAdmin() {
    AuthResult authResult =
        new AppAuthManager().authenticateBearerToken(session, session.getContext().getRealm());
    if (authResult == null) {
      throw new NotAuthorizedException(Constants.BEARER);
    } else if (authResult.getToken().getRealmAccess() == null
        || !authResult.getToken().getRealmAccess().isUserInRole(Constants.ADMIN)) {
      throw new ForbiddenException(Constants.DOES_NOT_HAVE_REALM_ADMIN_ROLE);
    }
  }

  
  //validateAuth
  //validateClientId
  //validateRedirectUri
  //getActionName
  
  
  @Override
  public Object getResource() {
    return this;
  }

  @Override
  public void close() {

  }
}
