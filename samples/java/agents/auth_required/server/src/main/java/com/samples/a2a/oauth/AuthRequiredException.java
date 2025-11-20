package com.samples.a2a.oauth;

/**
 * Exception thrown when a calendar operation requires OAuth2 authentication.
 * This exception is caught by the agent executor to trigger the OAuth2 flow.
 *
 * <p>Similar to how ADK uses 'adk_request_credential' function call events,
 * this exception provides a structured way to signal authentication requirements
 * rather than relying on string parsing.
 */
public class AuthRequiredException extends RuntimeException {

  /** The user ID that needs authentication. */
  private final String userId;

  /**
   * Constructor for AuthRequiredException.
   *
   * @param userIdParam the user ID that needs authentication
   */
  public AuthRequiredException(final String userIdParam) {
    super("Authentication required for user: " + userIdParam);
    this.userId = userIdParam;
  }

  /**
   * Get the user ID.
   *
   * @return the user ID
   */
  public String getUserId() {
    return userId;
  }
}
