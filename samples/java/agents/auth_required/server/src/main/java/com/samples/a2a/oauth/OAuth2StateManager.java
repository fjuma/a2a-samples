package com.samples.a2a.oauth;

import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.OidcClients;
import io.quarkus.oidc.client.Tokens;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Manages OAuth2 state and waiting tasks for the calendar tool.
 * Similar to calendar_agent/adk_agent_executor.py in the birthday planner example.
 */
@ApplicationScoped
public class OAuth2StateManager {

  /** Inject the OIDC clients to get the named client for Google Calendar. */
  @Inject OidcClients oidcClients;

  @ConfigProperty(name = "quarkus.http.port")
  int httpPort;

  @ConfigProperty(name = "google.oauth.client-id")
  String clientId;

  // Maps state token -> CompletableFuture waiting for callback
  // Similar to _awaiting_auth in calendar_agent/adk_agent_executor.py:62
  private final Map<String, CompletableFuture<AuthCallback>> awaitingAuth =
      new ConcurrentHashMap<>();

  // Maps user ID -> OIDC Tokens (managed by Quarkus)
  // Similar to _credentials in calendar_agent/adk_agent_executor.py:63
  private final Map<String, Tokens> userTokens = new ConcurrentHashMap<>();

  /** OAuth2 authorization details. */
  public static class AuthDetails {
    public String state;
    public String authUri;
    public CompletableFuture<AuthCallback> future;
    public String taskId;

    /**
     * Constructor.
     *
     * @param stateParam the state token
     * @param authUriParam the OAuth authorization URI
     * @param futureParam the future to wait for callback
     * @param taskIdParam the task ID
     */
    public AuthDetails(
        final String stateParam,
        final String authUriParam,
        final CompletableFuture<AuthCallback> futureParam,
        final String taskIdParam) {
      this.state = stateParam;
      this.authUri = authUriParam;
      this.future = futureParam;
      this.taskId = taskIdParam;
    }
  }

  /** OAuth2 callback data. */
  public static class AuthCallback {
    public String code;
    public String state;

    /**
     * Constructor.
     *
     * @param codeParam the authorization code
     * @param stateParam the state token
     */
    public AuthCallback(final String codeParam, final String stateParam) {
      this.code = codeParam;
      this.state = stateParam;
    }
  }

  /**
   * Prepare OAuth2 authorization request.
   * Similar to _prepare_auth_request in calendar_agent/adk_agent_executor.py:134-166
   *
   * @param taskId the task ID
   * @return the authorization details
   */
  public AuthDetails prepareAuthRequest(final String taskId) {
    // Generate state token (similar to line 156)
    final String state = UUID.randomUUID().toString();

    // Create Future to wait for callback (similar to line 157-158)
    final CompletableFuture<AuthCallback> future = new CompletableFuture<>();
    awaitingAuth.put(state, future);

    final String redirectUri = "http://localhost:" + httpPort + "/authenticate";
    final String scope = "https://www.googleapis.com/auth/calendar";

    // Build Google OAuth URL with properly encoded parameters
    try {
      final String authUri =
          "https://accounts.google.com/o/oauth2/auth"
              + "?client_id="
              + java.net.URLEncoder.encode(clientId, "UTF-8")
              + "&redirect_uri="
              + java.net.URLEncoder.encode(redirectUri, "UTF-8")
              + "&response_type=code"
              + "&scope="
              + java.net.URLEncoder.encode(scope, "UTF-8")
              + "&state="
              + state
              + "&access_type=offline"
              + "&prompt=consent";

      return new AuthDetails(state, authUri, future, taskId);
    } catch (java.io.UnsupportedEncodingException e) {
      throw new RuntimeException("UTF-8 encoding not supported", e);
    }
  }

  /**
   * Handle OAuth callback - just store code and state.
   * Similar to on_auth_callback in calendar_agent/adk_agent_executor.py:245-246
   *
   * @param state the state token
   * @param code the authorization code
   */
  public void handleCallback(final String state, final String code) {
    final CompletableFuture<AuthCallback> future = awaitingAuth.get(state);
    if (future != null) {
      future.complete(new AuthCallback(code, state)); // Resolves the Future!
    }
  }

  /**
   * Wait for callback and exchange code for tokens using OIDC client.
   * Similar to _complete_auth_processing in calendar_agent/adk_agent_executor.py:168-212
   *
   * @param state the state token
   * @param userId the user ID
   * @param timeoutSeconds timeout in seconds
   * @return a Uni that completes with the tokens
   */
  public Uni<Tokens> waitForCallbackAndExchangeToken(
      final String state, final String userId, final long timeoutSeconds) {
    final CompletableFuture<AuthCallback> future = awaitingAuth.get(state);
    if (future == null) {
      return Uni.createFrom()
          .failure(new IllegalStateException("No auth request for state: " + state));
    }

    return Uni.createFrom()
        .completionStage(future.orTimeout(timeoutSeconds, TimeUnit.SECONDS))
        .chain(
            callback -> {
              // Clean up (similar to line 196)
              awaitingAuth.remove(state);

              // Exchange authorization code for tokens
              final String redirectUri = "http://localhost:" + httpPort + "/authenticate";

              // Build the parameters for authorization code grant
              final Map<String, String> codeGrantParams =
                  Map.of(
                      "grant_type", "authorization_code",
                      "code", callback.code,
                      "redirect_uri", redirectUri);

              // Get the named OIDC client for Google Calendar
              OidcClient oidcClient = oidcClients.getClient("google-calendar");

              return oidcClient
                  .getTokens(codeGrantParams)
                  .onItem()
                  .invoke(tokens -> userTokens.put(userId, tokens));
            });
  }

  /**
   * Get valid access token for user (with automatic refresh!).
   * Similar to _ensure_auth in calendar_agent/adk_agent_executor.py:264-281
   *
   * @param userId the user ID
   * @return a Uni that completes with the access token, or null if no credentials
   */
  public Uni<String> getAccessToken(final String userId) {
    final Tokens tokens = userTokens.get(userId);

    if (tokens == null) {
      return Uni.createFrom().nullItem();
    }

    // Check if token is expired and refresh if needed
    if (tokens.isAccessTokenExpired()) {
      // Quarkus OIDC client handles refresh automatically!
      // Get the named OIDC client for Google Calendar
      OidcClient oidcClient = oidcClients.getClient("google-calendar");

      return oidcClient
          .refreshTokens(tokens.getRefreshToken())
          .onItem()
          .invoke(refreshedTokens -> userTokens.put(userId, refreshedTokens))
          .map(Tokens::getAccessToken);
    }

    return Uni.createFrom().item(tokens.getAccessToken());
  }

  /**
   * Check if user has valid credentials.
   *
   * @param userId the user ID
   * @return true if user has credentials
   */
  public boolean hasCredentials(final String userId) {
    return userTokens.containsKey(userId);
  }

  /**
   * Extract authorization code from callback URI.
   *
   * @param callbackUri the callback URI
   * @return the authorization code
   */
  public String extractCodeFromUri(final String callbackUri) {
    try {
      final java.net.URI uri = java.net.URI.create(callbackUri);
      final String query = uri.getQuery();

      for (final String param : query.split("&")) {
        final String[] pair = param.split("=");
        if ("code".equals(pair[0])) {
          return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
        }
      }

      throw new IllegalArgumentException("No code parameter in URI");
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid callback URI", e);
    }
  }

  /**
   * Extract state from callback URI.
   *
   * @param callbackUri the callback URI
   * @return the state token
   */
  public String extractStateFromUri(final String callbackUri) {
    try {
      final java.net.URI uri = java.net.URI.create(callbackUri);
      final String query = uri.getQuery();

      for (final String param : query.split("&")) {
        final String[] pair = param.split("=");
        if ("state".equals(pair[0])) {
          return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
        }
      }

      throw new IllegalArgumentException("No state parameter in URI");
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid callback URI", e);
    }
  }
}
