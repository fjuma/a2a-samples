package com.samples.a2a;

import com.samples.a2a.calendar.GoogleCalendarClient;
import com.samples.a2a.oauth.AuthContext;
import com.samples.a2a.oauth.OAuth2StateManager;
import dev.langchain4j.agent.tool.Tool;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Tools for interacting with Google Calendar API.
 * Uses real Google Calendar API calls via REST client with OAuth2 authentication.
 * Based on https://github.com/sberyozkin/quarkus-langchain4j-gemini
 */
@ApplicationScoped
public class GoogleCalendarTools {

  @Inject OAuth2StateManager stateManager;

  @Inject @RestClient GoogleCalendarClient calendarClient;

  /**
   * Get events from the user's calendar within a specific time range.
   *
   * @param calendarId the calendar ID (use "primary" for primary calendar)
   * @param timeMin minimum time in RFC3339 format (e.g., "2025-11-29T14:00:00-05:00")
   * @param timeMax maximum time in RFC3339 format (e.g., "2025-11-29T15:00:00-05:00")
   * @return list of events in the time range
   */
  @Tool("Get events from calendar in a specific time range")
  public Uni<String> getEvents(
      final String calendarId, final String timeMin, final String timeMax) {
    final String userId = AuthContext.getUserId();
    return executeWithAuth(
        userId,
        accessToken -> {
          try {
            GoogleCalendarClient.Events events =
                calendarClient.getEvents(
                    "Bearer " + accessToken, calendarId, timeMin, timeMax, 100);

            if (events.items() == null || events.items().isEmpty()) {
              return "No events found in the specified time range.";
            }

            StringBuilder result = new StringBuilder("Events:\n\n");
            int count = 1;
            for (GoogleCalendarClient.Event event : events.items()) {
              result.append(count++).append(". ");

              if (event.summary() != null) {
                result.append(event.summary());
              } else {
                result.append("(No title)");
              }

              if (event.start() != null && event.start().dateTime() != null) {
                result.append(" - ").append(formatDateTime(event.start().dateTime()));
              }

              if (event.end() != null && event.end().dateTime() != null) {
                // Calculate duration
                try {
                  LocalDateTime start = parseRFC3339(event.start().dateTime());
                  LocalDateTime end = parseRFC3339(event.end().dateTime());
                  long minutes = ChronoUnit.MINUTES.between(start, end);
                  result.append(" (").append(minutes).append(" minutes)");
                } catch (Exception e) {
                  // Skip duration if parsing fails
                }
              }

              result.append("\n");
            }

            return result.toString();
          } catch (Exception e) {
            return "Error getting events: " + e.getMessage();
          }
        });
  }


  /**
   * Schedule a new meeting in the user's calendar.
   *
   * @param calendarId the calendar ID (use "primary" for primary calendar)
   * @param title the meeting title
   * @param startTime start time in RFC3339 format (e.g., "2025-11-29T14:00:00-05:00")
   * @param endTime end time in RFC3339 format (e.g., "2025-11-29T15:00:00-05:00")
   * @return confirmation or auth-required indicator
   */
  @Tool("Schedule a new meeting in the user's Google Calendar")
  public Uni<String> scheduleMeeting(
      final String calendarId,
      final String title,
      final String startTime,
      final String endTime) {
    final String userId = AuthContext.getUserId();
    return executeWithAuth(
        userId,
        accessToken -> {
          try {
            GoogleCalendarClient.EventDateTime start =
                new GoogleCalendarClient.EventDateTime(
                    startTime, ZoneId.systemDefault().getId());
            GoogleCalendarClient.EventDateTime end =
                new GoogleCalendarClient.EventDateTime(
                    endTime, ZoneId.systemDefault().getId());

            GoogleCalendarClient.Event newEvent =
                new GoogleCalendarClient.Event(null, title, null, start, end);

            GoogleCalendarClient.Event createdEvent =
                calendarClient.addEvent("Bearer " + accessToken, calendarId, newEvent);

            // Calculate duration for display
            long minutes = 0;
            try {
              LocalDateTime startDateTime = parseRFC3339(startTime);
              LocalDateTime endDateTime = parseRFC3339(endTime);
              minutes = ChronoUnit.MINUTES.between(startDateTime, endDateTime);
            } catch (Exception e) {
              // Skip duration calculation if parsing fails
            }

            return "✓ Meeting scheduled successfully!\n"
                + "Title: "
                + title
                + "\n"
                + "Time: "
                + formatDateTime(createdEvent.start().dateTime())
                + "\n"
                + (minutes > 0 ? "Duration: " + minutes + " minutes\n" : "")
                + "Event ID: "
                + createdEvent.id()
                + "\n"
                + "The event has been added to your Google Calendar.";
          } catch (Exception e) {
            return "Error scheduling meeting: " + e.getMessage();
          }
        });
  }

  /**
   * Execute a calendar operation with OAuth2 authentication.
   * Handles credential checking and token retrieval.
   * If authentication is not available, marks auth required in context.
   *
   * @param userId the user ID
   * @param operation the operation to execute with the access token
   * @return the operation result or auth required message
   */
  private Uni<String> executeWithAuth(
      final String userId, final java.util.function.Function<String, String> operation) {

    // Check if we have credentials for this user
    if (!stateManager.hasCredentials(userId)) {
      // Mark auth required in thread-local context instead of throwing
      AuthContext.markAuthRequired(userId);
      return Uni.createFrom()
          .item(
              "I need permission to access your Google Calendar. "
                  + "Please authenticate to continue.");
    }

    // Get access token (will auto-refresh if expired)
    return stateManager
        .getAccessToken(userId)
        .chain(
            accessToken -> {
              if (accessToken == null) {
                // Mark auth required in thread-local context instead of throwing
                AuthContext.markAuthRequired(userId);
                return Uni.createFrom()
                    .item(
                        "I need permission to access your Google Calendar. "
                            + "Please authenticate to continue.");
              }

              // Execute the operation with the access token
              return Uni.createFrom().item(operation.apply(accessToken));
            });
  }

  /**
   * Format LocalDateTime to RFC3339 timestamp for Google Calendar API.
   *
   * @param dateTime the date/time to format
   * @return RFC3339 formatted string
   */
  private String formatToRFC3339(final LocalDateTime dateTime) {
    return dateTime.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
  }

  /**
   * Format RFC3339 timestamp to human-readable format.
   *
   * @param rfc3339 the RFC3339 timestamp
   * @return human-readable format
   */
  private String formatDateTime(final String rfc3339) {
    try {
      LocalDateTime dateTime = parseRFC3339(rfc3339);
      return dateTime.format(DateTimeFormatter.ofPattern("EEEE, MMMM d 'at' h:mm a"));
    } catch (Exception e) {
      return rfc3339; // Return original if parsing fails
    }
  }

  /**
   * Parse RFC3339 timestamp to LocalDateTime.
   *
   * @param rfc3339 the RFC3339 timestamp
   * @return LocalDateTime
   */
  private LocalDateTime parseRFC3339(final String rfc3339) {
    return LocalDateTime.parse(rfc3339, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
  }
}
