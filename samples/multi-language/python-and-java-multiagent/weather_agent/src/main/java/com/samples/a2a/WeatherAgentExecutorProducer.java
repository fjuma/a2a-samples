package com.samples.a2a;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.List;

import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.server.tasks.TaskUpdater;
import io.a2a.spec.JSONRPCError;
import io.a2a.spec.Message;
import io.a2a.spec.Part;
import io.a2a.spec.TaskState;
import io.a2a.spec.TextPart;
import io.a2a.spec.UnsupportedOperationError;

@ApplicationScoped
public class WeatherAgentExecutorProducer {

    @Inject
    WeatherAgent weatherAgent;

    // Thread pool for background execution
    private final Executor taskExecutor = Executors.newCachedThreadPool();
    
    // Track active sessions for potential cancellation
    private final ConcurrentHashMap<String, CompletableFuture<Void>> activeSessions = new ConcurrentHashMap<>();

    @Produces
    public AgentExecutor agentExecutor() {
        return new WeatherAgentExecutor(weatherAgent, taskExecutor, activeSessions);
    }

    private static class WeatherAgentExecutor implements AgentExecutor {

        private final WeatherAgent weatherAgent;
        private final Executor taskExecutor;
        private final ConcurrentHashMap<String, CompletableFuture<Void>> activeSessions;

        public WeatherAgentExecutor(WeatherAgent weatherAgent, Executor taskExecutor, 
                                  ConcurrentHashMap<String, CompletableFuture<Void>> activeSessions) {
            this.weatherAgent = weatherAgent;
            this.taskExecutor = taskExecutor;
            this.activeSessions = activeSessions;
        }

        @Override
        public void execute(RequestContext context, EventQueue eventQueue) throws JSONRPCError {
            TaskUpdater updater = new TaskUpdater(eventQueue, context.getTaskId(), context.getContextId());

            // Immediately notify that the task is submitted
            if (context.getTask() == null) {
                updater.submit();
            }
            updater.startWork();

            // Start background processing
            CompletableFuture<Void> taskFuture = CompletableFuture.runAsync(() -> {
                try {
                    processRequestInBackground(context, updater, eventQueue);
                } catch (Exception e) {
                    System.err.println("Weather agent execution failed: " + e.getMessage());
                    e.printStackTrace();
                }
            }, taskExecutor);

            // Track the active session
            activeSessions.put(context.getContextId(), taskFuture);

            // Return immediately - task continues in background
        }

        private void processRequestInBackground(RequestContext context, TaskUpdater updater, EventQueue eventQueue) {
            String contextId = context.getContextId();
            String taskId = context.getTaskId();
            
            try {
                // Check for interruption before starting work
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                
                // Extract text from message parts
                String userMessage = extractTextFromMessage(context.getMessage());
                
                // Call the weather agent with the user's message
                String response = weatherAgent.chat(userMessage);
                
                // Check for interruption after agent call
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                
                // Create response part
                TextPart responsePart = new TextPart(response, null);
                List<Part<?>> parts = List.of(responsePart);
                
                // Add response as artifact and complete the task
                updater.addArtifact(parts, null, null, null);
                updater.complete();
                
            } catch (Exception e) {
                // Task failed
                System.err.println("Weather agent task failed: " + contextId);
                e.printStackTrace();
                
                // Mark task as failed using TaskUpdater
                updater.failed();
                
            } finally {
                // Clean up active session
                activeSessions.remove(contextId);
            }
        }

        private String extractTextFromMessage(Message message) {
            StringBuilder textBuilder = new StringBuilder();
            
            if (message.getParts() != null) {
                for (Part part : message.getParts()) {
                    if (part instanceof TextPart textPart) {
                        textBuilder.append(textPart.getText());
                    }
                }
            }
            
            return textBuilder.toString();
        }

        @Override
        public void cancel(RequestContext context, EventQueue eventQueue) throws JSONRPCError {
            String contextId = context.getContextId();
            CompletableFuture<Void> taskFuture = activeSessions.get(contextId);
            
            if (taskFuture != null) {
                // Cancel the future
                taskFuture.cancel(true);
                activeSessions.remove(contextId);
                
                // Update task status to cancelled using TaskUpdater
                TaskUpdater updater = new TaskUpdater(eventQueue, context.getTaskId(), contextId);
                updater.updateStatus(TaskState.CANCELED, null, true);
            } else {
                System.out.println("Cancellation requested for inactive weather session: " + contextId);
            }
            
            // Note: The Python version throws UnsupportedOperationError, but since we implement
            // basic cancellation here, we could either throw it or handle it gracefully.
            // Following the Python version's pattern:
            throw new UnsupportedOperationError();
        }
    }
}