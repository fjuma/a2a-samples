package com.samples.a2a.calendar;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * REST client for Google Calendar API.
 *
 * <p>Requires Authorization header to be passed manually with each call.
 * Makes direct REST calls to Google Calendar API v3.
 */
@RegisterRestClient(configKey = "google-calendar-api")
@Path("/calendars")
public interface GoogleCalendarClient {

  /**
   * Get list of user's calendars.
   *
   * @param authorization the Authorization header (e.g., "Bearer ya29.xxx")
   * @return the list of calendars
   */
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  Calendars getCalendars(@HeaderParam("Authorization") String authorization);

  /**
   * Get events from a specific calendar within a time range.
   *
   * @param authorization the Authorization header (e.g., "Bearer ya29.xxx")
   * @param calendarId the calendar ID (use "primary" for primary calendar)
   * @param timeMin minimum time (RFC3339 timestamp)
   * @param timeMax maximum time (RFC3339 timestamp)
   * @param maxResults maximum number of results
   * @return the events
   */
  @GET
  @Path("/{calendarId}/events")
  @Produces(MediaType.APPLICATION_JSON)
  Events getEvents(
      @HeaderParam("Authorization") String authorization,
      @PathParam("calendarId") String calendarId,
      @QueryParam("timeMin") String timeMin,
      @QueryParam("timeMax") String timeMax,
      @QueryParam("maxResults") Integer maxResults);

  /**
   * Add a new event to a calendar.
   *
   * @param authorization the Authorization header (e.g., "Bearer ya29.xxx")
   * @param calendarId the calendar ID
   * @param event the event to add
   * @return the created event
   */
  @POST
  @Path("/{calendarId}/events")
  @Produces(MediaType.APPLICATION_JSON)
  Event addEvent(
      @HeaderParam("Authorization") String authorization,
      @PathParam("calendarId") String calendarId,
      Event event);

  /**
   * Update an existing event.
   *
   * @param authorization the Authorization header (e.g., "Bearer ya29.xxx")
   * @param calendarId the calendar ID
   * @param eventId the event ID
   * @param event the updated event
   * @return the updated event
   */
  @PUT
  @Path("/{calendarId}/events/{eventId}")
  @Produces(MediaType.APPLICATION_JSON)
  Event updateEvent(
      @HeaderParam("Authorization") String authorization,
      @PathParam("calendarId") String calendarId,
      @PathParam("eventId") String eventId,
      Event event);

  /**
   * Delete an event.
   *
   * @param authorization the Authorization header (e.g., "Bearer ya29.xxx")
   * @param calendarId the calendar ID
   * @param eventId the event ID
   */
  @DELETE
  @Path("/{calendarId}/events/{eventId}")
  void deleteEvent(
      @HeaderParam("Authorization") String authorization,
      @PathParam("calendarId") String calendarId,
      @PathParam("eventId") String eventId);

  /** Google Calendar list response. */
  record Calendars(List<Calendar> items) {}

  /** Google Calendar. */
  record Calendar(String id, String summary, String description) {}

  /** Google Calendar events response. */
  record Events(List<Event> items) {}

  /** Google Calendar event. */
  record Event(String id, String summary, String description, EventDateTime start,
      EventDateTime end) {}

  /** Event date/time. */
  record EventDateTime(String dateTime, String timeZone) {}
}
