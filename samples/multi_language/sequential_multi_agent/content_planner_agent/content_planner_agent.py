from google.adk.agents import Agent
from google.adk.tools import google_search


root_agent = Agent(
    name="content_planner_agent",
    model="gemini-2.5-flash-lite-preview-06-17",
    description=("Agent to create content outlines"),
    instruction=("You are a helpful agent who can provide an outline that a writer can use to create content."
                 "You will be given a high-level goal for the content that is needed."),
    tools=[google_search],
)
