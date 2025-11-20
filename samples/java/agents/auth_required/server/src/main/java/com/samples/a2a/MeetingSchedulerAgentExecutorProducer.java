package com.samples.a2a;

import com.samples.a2a.oauth.AuthContext;
import com.samples.a2a.oauth.OAuth2StateManager;
import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.server.tasks.TaskUpdater;
import io.a2a.spec.JSONRPCError;
import io.a2a.spec.Message;
import io.a2a.spec.Part;
import io.a2a.spec.Task;
import io.a2a.spec.TaskNotCancelableError;
import io.a2a.spec.TaskState;
import io.a2a.spec.TextPart;
import io.quarkus.oidc.client.Tokens;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/** Producer for Meeting Scheduler agent executor. */
@ApplicationScoped
public final class MeetingSchedulerAgentExecutorProducer {

  /** The Meeting Scheduler agent instance. */
  @Inject private MeetingSchedulerAgent meetingSchedulerAgent;

  /** OAuth2 state manager for Google Calendar auth. */
  @Inject private OAuth2StateManager stateManager;

  /**
   * Produces the agent executor for the Meeting Scheduler agent.
   *
   * @return the configured agent executor
   */
  @Produces
  public AgentExecutor agentExecutor() {
    return new MeetingSchedulerAgentExecutor(meetingSchedulerAgent, stateManager);
  }

  /**
   * Meeting Scheduler agent executor implementation with OAuth2 support.
   * Similar to calendar_agent/adk_agent_executor.py:59-239
   */
  private static class MeetingSchedulerAgentExecutor implements AgentExecutor {

    /** The Meeting Scheduler agent instance. */
    private final MeetingSchedulerAgent agent;

    /** OAuth2 state manager for handling Google Calendar authentication. */
    private final OAuth2StateManager stateManager;

    /** Timeout for waiting for OAuth2 authentication (60 seconds). */
    private static final long AUTH_TIMEOUT_SECONDS = 60;

    /**
     * Constructor for MeetingSchedulerAgentExecutor.
     *
     * @param meetingSchedulerAgentInstance the Meeting Scheduler agent instance
     * @param oauth2StateManager the OAuth2 state manager
     */
    MeetingSchedulerAgentExecutor(
        final MeetingSchedulerAgent meetingSchedulerAgentInstance,
        final OAuth2StateManager oauth2StateManager) {
      this.agent = meetingSchedulerAgentInstance;
      this.stateManager = oauth2StateManager;
    }

    /**
     * Execute the agent task, handling OAuth2 authentication if needed.
     * Similar to calendar_agent/adk_agent_executor.py:221-238
     */
    @Override
    public void execute(final RequestContext context, final EventQueue eventQueue)
        throws JSONRPCError {
      final TaskUpdater updater = new TaskUpdater(context, eventQueue);

      // mark the task as submitted and start working on it
      if (context.getTask() == null) {
        updater.submit();
      }
      updater.startWork();

      // extract the text from the message
      final String question = extractTextFromMessage(context.getMessage());

      // Get user ID from context (for OAuth2 credential storage)
      final String userId = getUserId(context);

      // Generate a unique memory ID for this request for fresh chat memory
      final String memoryId = UUID.randomUUID().toString();

      // Calculate current date/time to give the LLM context for interpreting relative dates
      final String currentDate =
          java.time.ZonedDateTime.now()
              .format(
                  java.time.format.DateTimeFormatter.ofPattern(
                      "EEEE, MMMM d, yyyy 'at' h:mm a z"));

      // Set task ID and user ID in context so tools can access them
      AuthContext.setTaskId(context.getTaskId());
      AuthContext.setUserId(userId);

      try {
        // call the Meeting Scheduler agent with the request
        // Tools may mark auth required in AuthContext if they need credentials
        // Similar to how ADK detects 'adk_request_credential' function call events
        final String response = agent.handleRequest(memoryId, question, currentDate);

        // Check if any tool marked auth as required
        if (AuthContext.isAuthRequired()) {
          handleOAuthRequired(context, eventQueue, updater, userId, memoryId, question);
          return;
        }

        // create the response part
        final TextPart responsePart = new TextPart(response, null);
        final List<Part<?>> parts = List.of(responsePart);

        // add the response as an artifact and complete the task
        updater.addArtifact(parts, null, null, null);
        updater.complete();
      } finally {
        // Always clear the auth context when task execution completes
        AuthContext.clear();
      }
    }

    /**
     * Handle OAuth2 authentication required scenario.
     * Similar to calendar_agent/adk_agent_executor.py:92-132
     */
    private void handleOAuthRequired(
        final RequestContext context,
        final EventQueue eventQueue,
        final TaskUpdater updater,
        final String userId,
        final String memoryId,
        final String question) {

      // Prepare OAuth2 auth request
      final OAuth2StateManager.AuthDetails authDetails =
          stateManager.prepareAuthRequest(context.getTaskId());

      // Update task status to AUTH_REQUIRED with the OAuth URL
      final String authMessageText =
          "Authorization is required to access your Google Calendar. "
              + "Please visit this URL to authenticate: "
              + authDetails.authUri;
      final Message authMessage = createAgentMessage(authMessageText);
      updater.updateStatus(TaskState.AUTH_REQUIRED, authMessage);

      // Wait for OAuth callback and resume task - BLOCKING
      // We must block here so execute() doesn't return and close the event stream
      // Similar to calendar_agent/adk_agent_executor.py:128-132
      try {
        stateManager
            .waitForCallbackAndExchangeToken(authDetails.state, userId, AUTH_TIMEOUT_SECONDS)
            .onFailure(TimeoutException.class)
            .recoverWithItem(
                err -> {
                  try {
                    Message timeoutMessage =
                        createAgentMessage("Timed out waiting for authorization.");
                    updater.updateStatus(TaskState.FAILED, timeoutMessage);
                  } finally {
                    // Clear auth context on timeout
                    AuthContext.clear();
                  }
                  return null;
                })
            .onItem()
            .ifNotNull()
            .call(
                tokens -> {
                  return Uni.createFrom()
                      .item(
                          () -> {
                            try {
                              // Update status to working
                              Message workingMessage =
                                  createAgentMessage("Authentication successful, processing...");
                              updater.updateStatus(TaskState.WORKING, workingMessage);

                              // Set task ID and user ID in context for retry
                              AuthContext.setTaskId(context.getTaskId());
                              AuthContext.setUserId(userId);

                              // Retry the agent call now that we have credentials
                              // Use a fresh memory ID to avoid confusion from previous failed
                              // attempt
                              final String retryMemoryId = UUID.randomUUID().toString();

                              // Recalculate current date for retry (in case time has elapsed)
                              final String retryCurrentDate =
                                  java.time.ZonedDateTime.now()
                                      .format(
                                          java.time.format.DateTimeFormatter.ofPattern(
                                              "EEEE, MMMM d, yyyy 'at' h:mm a z"));

                              final String retryResponse =
                                  agent.handleRequest(retryMemoryId, question, retryCurrentDate);

                              // Complete the task with the response
                              final TextPart responsePart = new TextPart(retryResponse, null);
                              updater.addArtifact(List.of(responsePart), null, null, null);
                              updater.complete();
                              return null;
                            } catch (Exception e) {
                              System.err.println("Error during retry: " + e.getMessage());
                              e.printStackTrace();
                              throw e;
                            } finally {
                              // Clear auth context after retry
                              AuthContext.clear();
                            }
                          })
                      .runSubscriptionOn(
                          io.smallrye.mutiny.infrastructure.Infrastructure
                              .getDefaultWorkerPool());
                })
            .await()
            .atMost(java.time.Duration.ofSeconds(AUTH_TIMEOUT_SECONDS + 10));
      } catch (Exception e) {
        System.err.println("OAuth flow failed: " + e.getMessage());
        e.printStackTrace();
      }
    }

    /**
     * Extract user ID from request context.
     * For this sample, we use a fixed userId so credentials persist across CLI client sessions.
     * In production, you would extract the user ID from JWT claims or another authentication mechanism.
     *
     * @param context the request context
     * @return the user ID
     */
    private String getUserId(final RequestContext context) {
      // Use a fixed userId for this sample/demo
      // This ensures Google Calendar credentials persist across CLI client sessions
      // (even though contextId changes each time the CLI client runs)
      //
      // In production, you would extract the user identity from:
      // - JWT token claims (requires request context to be active)
      // - A user authentication system
      // - Database lookup based on contextId
      return "cli-user";
    }

    private String extractTextFromMessage(final Message message) {
      final StringBuilder textBuilder = new StringBuilder();
      if (message.getParts() != null) {
        for (final Part<?> part : message.getParts()) {
          if (part instanceof TextPart textPart) {
            textBuilder.append(textPart.getText());
          }
        }
      }
      return textBuilder.toString();
    }

    /**
     * Create an agent message with the given text.
     *
     * @param text the message text
     * @return a Message with AGENT role
     */
    private Message createAgentMessage(final String text) {
      final TextPart textPart = new TextPart(text, null);
      return new Message.Builder()
          .role(Message.Role.AGENT)
          .parts(List.of(textPart))
          .build();
    }

    @Override
    public void cancel(final RequestContext context, final EventQueue eventQueue)
        throws JSONRPCError {
      final Task task = context.getTask();

      if (task.getStatus().state() == TaskState.CANCELED) {
        // task already cancelled
        throw new TaskNotCancelableError();
      }

      if (task.getStatus().state() == TaskState.COMPLETED) {
        // task already completed
        throw new TaskNotCancelableError();
      }

      // cancel the task
      final TaskUpdater updater = new TaskUpdater(context, eventQueue);
      updater.cancel();
    }
  }
}
