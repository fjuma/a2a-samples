package com.samples.a2a;

import io.a2a.server.PublicAgentCard;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentInterface;
import io.a2a.spec.AgentSkill;
import io.a2a.spec.AuthorizationCodeOAuthFlow;
import io.a2a.spec.ClientCredentialsOAuthFlow;
import io.a2a.spec.OAuth2SecurityScheme;
import io.a2a.spec.OAuthFlows;
import io.a2a.spec.TransportProtocol;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Producer for Meeting Scheduler agent card configuration. */
@ApplicationScoped
public final class MeetingSchedulerAgentCardProducer {

  /** The HTTP port for the agent service. */
  @Inject
  @ConfigProperty(name = "quarkus.http.port")
  private int httpPort;

  /** The HTTP port for Keycloak. */
  @Inject
  @ConfigProperty(name = "quarkus.keycloak.devservices.port")
  private int keycloakPort;

  /**
   * Produces the agent card for the Meeting Scheduler agent.
   *
   * @return the configured agent card
   */
  @Produces
  @PublicAgentCard
  public AgentCard agentCard() {
    // Keycloak OAuth2 for A2A server authentication
    ClientCredentialsOAuthFlow keycloakFlow =
        new ClientCredentialsOAuthFlow(
            null,
            Map.of("openid", "openid", "profile", "profile"),
            "http://localhost:"
                + keycloakPort
                + "/realms/quarkus/protocol/openid-connect/token");

    OAuth2SecurityScheme keycloakScheme =
        new OAuth2SecurityScheme.Builder()
            .flows(new OAuthFlows.Builder().clientCredentials(keycloakFlow).build())
            .build();

    // Google Calendar OAuth2 for tool authentication
    // Similar to calendar_agent/__main__.py:96-110
    AuthorizationCodeOAuthFlow googleCalendarFlow =
        new AuthorizationCodeOAuthFlow(
            "https://accounts.google.com/o/oauth2/auth",
            null,
            Map.of("https://www.googleapis.com/auth/calendar", "Access Google Calendar"),
            "https://oauth2.googleapis.com/token");

    OAuth2SecurityScheme googleCalendarScheme =
        new OAuth2SecurityScheme.Builder()
            .description("OAuth2 for Google Calendar API")
            .flows(new OAuthFlows.Builder().authorizationCode(googleCalendarFlow).build())
            .build();

    return new AgentCard.Builder()
        .name("Meeting Scheduler Agent")
        .description(
            "A smart meeting scheduler that helps you manage your Google Calendar. "
                + "Check availability, find free time slots, schedule meetings, "
                + "and view upcoming events.")
        .preferredTransport(TransportProtocol.JSONRPC.asString())
        .url("http://localhost:" + httpPort)
        .version("1.0.0")
        .documentationUrl("http://example.com/docs")
        .capabilities(
            new AgentCapabilities.Builder()
                .streaming(true)
                .pushNotifications(false)
                .stateTransitionHistory(false)
                .build())
        .defaultInputModes(List.of("text"))
        .defaultOutputModes(List.of("text"))
        .security(
            List.of(
                Map.of(OAuth2SecurityScheme.OAUTH2, List.of("profile")),
                Map.of("GoogleCalendarOAuth", List.of("https://www.googleapis.com/auth/calendar"))))
        .securitySchemes(
            Map.of(
                OAuth2SecurityScheme.OAUTH2, keycloakScheme,
                "GoogleCalendarOAuth", googleCalendarScheme))
        .skills(
            List.of(
                new AgentSkill.Builder()
                    .id("check_availability")
                    .name("Check Calendar Availability")
                    .description("Check if you're available at a specific time")
                    .tags(List.of("calendar", "scheduling", "availability"))
                    .examples(
                        List.of(
                            "Am I free tomorrow at 2pm?",
                            "Do I have any conflicts on Friday afternoon?"))
                    .build(),
                new AgentSkill.Builder()
                    .id("find_free_slots")
                    .name("Find Free Time Slots")
                    .description("Find available time slots in your calendar")
                    .tags(List.of("calendar", "scheduling", "availability"))
                    .examples(
                        List.of(
                            "When am I free this week for a 1-hour meeting?",
                            "Find me 30-minute slots next Monday"))
                    .build(),
                new AgentSkill.Builder()
                    .id("list_meetings")
                    .name("List Upcoming Meetings")
                    .description("View your upcoming calendar events")
                    .tags(List.of("calendar", "meetings"))
                    .examples(
                        List.of(
                            "What's on my calendar today?",
                            "Show me my meetings this week"))
                    .build(),
                new AgentSkill.Builder()
                    .id("schedule_meeting")
                    .name("Schedule a Meeting")
                    .description("Create a new event in your calendar")
                    .tags(List.of("calendar", "scheduling", "meetings"))
                    .examples(
                        List.of(
                            "Schedule a team sync tomorrow at 3pm for 30 minutes",
                            "Add a client call on Friday at 2pm"))
                    .build()))
        .protocolVersion("0.3.0")
        .additionalInterfaces(
            List.of(
                new AgentInterface(
                    TransportProtocol.JSONRPC.asString(), "http://localhost:" + httpPort)))
        .build();
  }
}
