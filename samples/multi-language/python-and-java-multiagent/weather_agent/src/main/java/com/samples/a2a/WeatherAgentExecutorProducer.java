package com.samples.a2a;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.server.tasks.TaskUpdater;
import io.a2a.spec.A2A;
import io.a2a.spec.JSONRPCError;
import io.a2a.spec.Message;
import io.a2a.spec.UnsupportedOperationError;

@ApplicationScoped
public class WeatherAgentExecutorProducer {

    @Inject
    WeatherAgent weatherAgent;

    @Produces
    public AgentExecutor agentExecutor() {
        return new WeatherAgentExecutor(weatherAgent);
    }

    private static class WeatherAgentExecutor implements AgentExecutor {

        private final WeatherAgent weatherAgent;

        public WeatherAgentExecutor(WeatherAgent weatherAgent) {
            this.weatherAgent = weatherAgent;
        }

        @Override
        public void execute(RequestContext context, EventQueue eventQueue) throws JSONRPCError {
            // temporarily hardcoding a message to test
            // // doesn't work, missing a tool to get forecast by city similar to the python one here
            // https://github.com/google-a2a/a2a-samples/blob/main/samples/python/agents/airbnb_planner_multiagent/weather_agent/weather_mcp.py#L177
            // String result = weatherAgent.chat("tell me about the weather in LA, CA");

            // a message like this does work
            // String result = weatherAgent.chat("weather alert for LA, CA");

            // TODO: Need to update this to be similar to what's done in the python executor:
            // https://github.com/google-a2a/a2a-samples/blob/main/samples/python/agents/airbnb_planner_multiagent/weather_agent/weather_executor.py
            /*TaskUpdater updater = new TaskUpdater(eventQueue, context.getTaskId(), context.getContextId());

            // immediately notify that the task is submitted
            if (context.getTask() == null) {
                updater.submit();
            }
            updater.startWork();

            // await process request
            ...
             */

        }

        @Override
        public void cancel(RequestContext context, EventQueue eventQueue) throws JSONRPCError {
            throw new UnsupportedOperationError();
        }
    }
}