package com.samples.a2a.client;

import com.samples.a2a.client.util.EventHandlerUtil;
import io.a2a.client.Client;
import io.a2a.client.ClientEvent;
import io.a2a.client.config.ClientConfig;
import io.a2a.client.transport.jsonrpc.JSONRPCTransport;
import io.a2a.client.transport.jsonrpc.JSONRPCTransportConfigBuilder;
import io.a2a.client.transport.spi.interceptors.auth.AuthInterceptor;
import io.a2a.client.transport.spi.interceptors.auth.CredentialService;
import io.a2a.spec.AgentCard;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Test client utility for creating A2A clients with JSONRPC transport
 * and OAuth2 authentication.
 *
 * <p>This class encapsulates the complexity of setting up A2A clients with
 * JSONRPC transport and Keycloak OAuth2 authentication, providing simple
 * methods to create configured clients for testing and development.
 */
public final class TestClient {

  private TestClient() {
  }

  /**
   * Creates an A2A client with JSONRPC transport and OAuth2 authentication.
   *
   * @param agentCard the agent card to connect to
   * @param messageResponse CompletableFuture for handling responses
   * @return configured A2A client
   */
  public static Client createClient(
      final AgentCard agentCard,
      final CompletableFuture<String> messageResponse) {

    // Create consumers for handling client events
    List<BiConsumer<ClientEvent, AgentCard>> consumers =
        EventHandlerUtil.createEventConsumers(messageResponse);

    // Create error handler for streaming errors
    Consumer<Throwable> streamingErrorHandler =
        EventHandlerUtil.createStreamingErrorHandler(messageResponse);

    // Create credential service for OAuth2 authentication
    CredentialService credentialService
            = new KeycloakOAuth2CredentialService();

    // Create shared auth interceptor
    AuthInterceptor authInterceptor = new AuthInterceptor(credentialService);

    // Create the A2A client with JSONRPC transport
    try {
      return Client.builder(agentCard)
          .addConsumers(consumers)
          .streamingErrorHandler(streamingErrorHandler)
          .withTransport(
              JSONRPCTransport.class,
              new JSONRPCTransportConfigBuilder()
                  .addInterceptor(authInterceptor)
                  .build())
          .clientConfig(new ClientConfig.Builder().build())
          .build();
    } catch (Exception e) {
      throw new RuntimeException("Failed to create A2A client", e);
    }
  }
}
