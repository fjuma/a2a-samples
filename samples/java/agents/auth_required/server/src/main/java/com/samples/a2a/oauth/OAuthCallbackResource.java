package com.samples.a2a.oauth;

import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * OAuth callback endpoint for handling Google OAuth redirects.
 * Similar to calendar_agent/oauth_helpers.py:115-155
 */
@Path("/authenticate")
public class OAuthCallbackResource {

  @Inject OAuth2StateManager stateManager;

  @Inject Template authSuccess;

  @Inject Template authError;

  /**
   * Handle OAuth callback from Google.
   *
   * @param state the state parameter
   * @param code the authorization code
   * @param error optional error parameter
   * @return HTML response
   */
  @GET
  @Produces(MediaType.TEXT_HTML)
  public String callback(
      @QueryParam("state") final String state,
      @QueryParam("code") final String code,
      @QueryParam("error") final String error) {

    // Handle error case
    if (error != null) {
      return authError.data("error", error).render();
    }

    // Validate parameters
    if (state == null || code == null) {
      return authError.data("error", "Missing state or code parameter").render();
    }

    try {
      // Complete the authorization flow
      stateManager.handleCallback(state, code);
      return authSuccess.instance().render();
    } catch (Exception e) {
      System.err.println("Error handling OAuth callback: " + e.getMessage());
      e.printStackTrace();
      return authError.data("error", "Failed to complete authentication: " + e.getMessage()).render();
    }
  }
}
