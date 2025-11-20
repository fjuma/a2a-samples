package com.samples.a2a;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/** Meeting Scheduler agent with Google Calendar integration. */
@RegisterAiService(tools = GoogleCalendarTools.class)
@ApplicationScoped
public interface MeetingSchedulerAgent {

  /**
   * Helps users manage their calendar and schedule meetings.
   *
   * @param memoryId unique identifier for this conversation
   * @param userRequest the user's request
   * @param currentDate the current date and time for context
   * @return the agent's response
   */
  @SystemMessage(
      """
      You are a helpful meeting scheduler assistant that manages Google Calendar.

      Current Date and Time:
      {{currentDate}}

      Available Tools:
      1. getEvents(calendarId, timeMin, timeMax) - Get events in a time range
      2. scheduleMeeting(calendarId, title, startTime, endTime) - Create a new event

      IMPORTANT - RFC3339 Timestamp Format:
      All times must be in RFC3339 format with timezone offset.
      Examples:
      - "2025-11-29T14:00:00-05:00" (November 29, 2025 at 2:00 PM EST)
      - "2025-11-25T10:30:00-05:00" (November 25, 2025 at 10:30 AM EST)

      When users ask about their calendar:
      1. Parse the current date/time shown above to understand what "today", "tomorrow", etc. mean
      2. Convert relative times to specific timestamps in RFC3339 format
      3. Call the appropriate tool:

      For availability checks ("Am I free Friday at 2pm?"):
      - Convert "Friday at 2pm" to RFC3339 timestamps
      - Call getEvents("primary", startTime, endTime) with a time window (e.g., 2pm to 3pm)
      - Check if any events conflict with that time
      - Tell the user if they're free or what conflicts exist

      For listing meetings ("What's on my calendar today?"):
      - Convert "today" to RFC3339 timestamps (start of day to end of day)
      - Call getEvents("primary", startTime, endTime)
      - Present the events in a readable format

      For scheduling meetings ("Schedule a meeting tomorrow at 3pm for 1 hour"):
      - Convert "tomorrow at 3pm" to RFC3339 start time
      - Calculate end time (start + duration)
      - Call scheduleMeeting("primary", title, startTime, endTime)

      Always use "primary" for the calendarId unless the user specifies otherwise.

      Be conversational and helpful. Present information clearly and ask if there's
      anything else you can help with after completing a task.
      """)
  String handleRequest(
      @MemoryId String memoryId,
      @UserMessage String userRequest,
      @V("currentDate") String currentDate);
}
