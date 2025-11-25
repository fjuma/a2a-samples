# Meeting Scheduler Agent with Multi-Level OAuth2 Security

This sample agent helps you manage your Google Calendar by scheduling meetings, checking availability, and managing your events.

This sample demonstrates **two levels of OAuth2 authentication**:
1. **Keycloak OAuth2** for A2A server authentication (securing the agent itself)
2. **Google OAuth2** for Google Calendar API access (securing access to user calendar data)

The agent is built using Quarkus LangChain4j and makes use of the [A2A Java](https://github.com/a2aproject/a2a-java) SDK.

## Architecture Overview

```
┌─────────────┐     Keycloak OAuth2      ┌──────────────────────┐
│  A2A Client ├────────────────────────►│  Meeting Scheduler   │
└─────────────┘   (Access the agent)     │      Agent           │
                                         │                      │
                                         │  ┌────────────────┐  │
                                         │  │ Calendar Tools │  │
                                         │  └────────┬───────┘  │
                                         └───────────┼──────────┘
                                                     │
                                          Google OAuth2
                                       (Access user calendar)
                                                     │
                                                     ▼
                                         ┌──────────────────┐
                                         │ Google Calendar  │
                                         │      API         │
                                         └──────────────────┘
```

## Features

- **Check Availability**: "Am I free on Friday at 2pm?"
- **List Meetings**: "What's on my calendar today?"
- **Schedule Meetings**: "Schedule a meeting tomorrow at 3pm for 1 hour called Sync"

## Prerequisites

- Java 17 or higher
- Access to an LLM and API Key (Google Gemini)
- A working container runtime (Docker or [Podman](https://quarkus.io/guides/podman))
- A [Google OAuth Client](https://developers.google.com/identity/openid-connect/openid-connect#getcredentials)
  - Configure your OAuth client to handle redirect URLs at `http://localhost:11000/authenticate`

>**NOTE**: This sample uses Quarkus Dev Services to automatically create and configure a Keycloak instance for A2A server authentication. For more details on using Podman with Quarkus, see this [guide](https://quarkus.io/guides/podman).

## Getting Started

### 1. Set Up Google OAuth2 Credentials

1. **Create a new project** in the [Google Cloud Console](https://console.cloud.google.com/)
   - Follow the instructions [here](https://developers.google.com/workspace/guides/create-project)

2. **Enable the Google Calendar API**
   - Go to "APIs & Services" for your project > "Library"
   - Search for "Google Calendar API"
   - Click on it and click "ENABLE"

3. **Configure OAuth consent screen**
   - Follow the instructions [here](https://developers.google.com/workspace/guides/configure-oauth-consent)
   - Fill in the required fields, e.g.,:
     - App name: "Meeting Scheduler Agent" (or your preferred name)
     - User support email: Your email address
     - Developer contact information: Your email address
     - Be sure to select External for user type and add yourself as a test user
     - When adding a scope, search for "Google Calendar API" and select the `https://www.googleapis.com/auth/calendar` scope

4. **Create OAuth 2.0 Client ID**
   - Go to "Clients" for your project and create a new client
   - Select "Web application" as application type
   - Enter a name (e.g., "Meeting Scheduler Agent")
   - Under "Authorized redirect URIs", click "ADD URI"
   - Add: `http://localhost:11000/authenticate`
   - Click "CREATE"
   - Copy the **Client ID** and **Client Secret** (you'll need these for configuration)

More details can be found in the [Google Identity documentation](https://developers.google.com/identity/openid-connect/openid-connect#getcredentials).

### 2. Configure the A2A Server Agent

Navigate to the `auth_required/server` directory:

```bash
cd samples/java/agents/auth_required/server
```

Create a `.env` file with your API keys:

```bash
# Gemini API Key
QUARKUS_LANGCHAIN4J_AI_GEMINI_API_KEY=your_gemini_api_key_here

# Google OAuth2 Credentials
GOOGLE_CLIENT_ID=your_google_client_id_here
GOOGLE_CLIENT_SECRET=your_google_client_secret_here
```

### 3. Start the A2A Server Agent

**NOTE:** By default, the agent will start on port 11000. To override this, add the `-Dquarkus.http.port=YOUR_PORT` option.

```bash
mvn quarkus:dev
```

The agent will:
- Start Keycloak Dev Services on port 11001
- Expose the Meeting Scheduler agent on port 11000
- Support JSON-RPC transport

### 4. Run the A2A Java Client

The Java `TestClient` communicates with the Meeting Scheduler Agent using the A2A Java SDK.

**Prerequisites:**
- [JBang installed](https://www.jbang.dev/documentation/jbang/latest/installation.html)

**Run the client:**

```bash
cd samples/java/agents/auth_required/client/src/main/java/com/samples/a2a/client
jbang TestClientRunner.java --message "Am I free on Friday at 2pm?"
```

**Optional parameters:**

```bash
# Custom server URL
jbang TestClientRunner.java --server-url http://localhost:11000

# Combine multiple options
jbang TestClientRunner.java --server-url http://localhost:11000 --message "What's on my calendar today?"
```

## OAuth2 Flow Walkthrough

This sample demonstrates both a secured A2A server agent (via Keycloak OAuth2) and in-task authentication (via Google OAuth2).

### Keycloak Authentication (A2A Server Security)

1. **Client connects** to the Meeting Scheduler agent
2. **Keycloak requires authentication** using client credentials flow
3. **Client obtains token** from Keycloak
4. **Client sends A2A request** with Keycloak bearer token

### Google Calendar Authentication (Tool Security - CLI Flow)

When the agent needs to access Google Calendar **from a CLI client**:

1. **Agent calls calendar tool** (e.g., `getEvents`)
2. **Tool checks for credentials** - if missing, marks `AUTH_REQUIRED` in context
3. **Agent executor detects auth needed** - suspends task with state `AUTH_REQUIRED`
4. **CLI client receives AUTH_REQUIRED status update**
5. **Client displays OAuth URL prominently in terminal**:
   ```
   ================================================================================
   🔐 AUTHENTICATION REQUIRED
   ================================================================================

   Authorization is required to access your Google Calendar.
   Please visit this URL to authenticate: https://accounts.google.com/...

   📋 OAuth URL:
      https://accounts.google.com/o/oauth2/auth?client_id=...&state=abc123...

   Please complete the following steps:
     1. Copy the URL above
     2. Open it in your web browser
     3. Sign in to your Google account
     4. Grant calendar access permissions
     5. Return to this terminal

   ⏳ Waiting for authentication... (60 second timeout)
   ================================================================================
   ```
6. **User manually copies URL and opens in browser**
7. **User grants calendar access** in browser
8. **Google redirects to callback** (`http://localhost:11000/authenticate?code=...&state=...`)
9. **Agent receives callback** and exchanges code for tokens using Quarkus OIDC Client
10. **Task resumes automatically** on the server side
11. **Client receives task updates** and sees completion
12. **Calendar operation executes** successfully

**CLI-Specific Considerations:**
- **No automatic redirect**: User must manually copy/paste URL into browser
- **Out-of-band flow**: Browser and CLI are separate - callback happens server-side
- **Task polling**: CLI client continues listening for task status updates
- **Timeout handling**: 60-second timeout if user doesn't authenticate

**Key Implementation Details:**
- **Context-based auth detection**: Tools call `AuthContext.markAuthRequired()` when credentials are missing
- **Server-side state management**: Uses `CompletableFuture` to suspend tasks waiting for OAuth callback (see `OAuth2StateManager.java`)
- **Client-side handling**: `EventHandlerUtil.handleAuthRequired()` detects `AUTH_REQUIRED` state and displays URL
- **Automatic token refresh**: Quarkus OIDC Client handles token expiration
- **Credential storage**: Google tokens are stored in-memory using a fixed userId (`"cli-user"`) for this sample
- **Callback endpoint**: `/authenticate` handles OAuth2 redirects from browser

## Example Conversations

### First-time user (requires authentication):

**Terminal Output:**
```
$ jbang TestClientRunner.java --message "Am I free on Friday at 2pm?"

Connecting to Meeting Scheduler Agent...
Received status-update: submitted
Received status-update: working
Received status-update: auth_required

================================================================================
🔐 AUTHENTICATION REQUIRED
================================================================================

Authorization is required to access your Google Calendar.
Please visit this URL to authenticate: https://accounts.google.com/...

📋 OAuth URL:
   https://accounts.google.com/o/oauth2/auth?client_id=123.apps.googleusercontent.com&redirect_uri=http://localhost:11000/authenticate&response_type=code&scope=https://www.googleapis.com/auth/calendar&state=a1b2c3d4&access_type=offline&prompt=consent

Please complete the following steps:
  1. Copy the URL above
  2. Open it in your web browser
  3. Sign in to your Google account
  4. Grant calendar access permissions
  5. Return to this terminal

⏳ Waiting for authentication... (60 second timeout)
================================================================================

[User opens URL in browser and authenticates]

Received status-update: working
Received status-update: completed
Response: You are available on Friday at 2pm. No conflicts found.
```

### Returning user (has credentials):

**Terminal Output:**
```
$ jbang TestClientRunner.java --message "What's on my calendar today?"

Connecting to Meeting Scheduler Agent...
Received status-update: submitted
Received status-update: working
Received status-update: completed
Final response: On your calendar today, Monday, November 24, 2025, you have:
1. Team Standup - Monday, November 24 at 10:00 AM (30 minutes)
2. Project Review - Monday, November 24 at 2:00 PM (60 minutes)
```

### Scheduling a meeting:

**Terminal Output:**
```
$ jbang TestClientRunner.java --message "Schedule a meeting tomorrow at 3pm for 1 hour call Sync"

Connecting to Meeting Scheduler Agent...
Received status-update: submitted
Received status-update: working
Received status-update: completed
Final response: OK. I've scheduled your "Sync" meeting for tomorrow, Tuesday, November 25, 2025, at 3:00 PM EST for 1 hour.
```

## CLI OAuth Flow Diagram

Here's how the OAuth2 flow works with a CLI client:

```
┌─────────────┐                          ┌──────────────────────┐
│ CLI Client  │                          │  Meeting Scheduler   │
│             │                          │      Agent           │
└──────┬──────┘                          └──────────┬──────────┘
       │                                            │
       │  1. "Am I free on Friday at 2pm?"          │
       ├───────────────────────────────────────────►│
       │                                            │
       │  2. Task submitted                         │
       │◄───────────────────────────────────────────┤
       │                                            │
       │                                            ├─┐ 3. Check credentials
       │                                            │ │    → None found
       │                                            │◄┘
       │                                            │
       │  4. Task state: AUTH_REQUIRED              ├─┐ 4. Prepare OAuth URL
       │     + OAuth URL                            │ │    with state token
       │◄───────────────────────────────────────────┤◄┘
       │                                            │
       ├─┐ 5. Extract and display URL               │
       │ │    prominently in terminal               │
       │◄┘                                          │
       │                                            │
┌──────┴──────┐                                     │
│ User copies │                                     │
│ URL and     │                                     │
│ opens in    │                                     │
│ browser     │                                     │
└──────┬──────┘                                     │
       │                                            │
       │          ┌─────────────────┐               │
       │          │  Web Browser    │               │
       │          │                 │               │
       │  6. Visit│  Google OAuth   │               │
       ├─────────►│  Consent Page   │               │
       │          │                 │               │
       │  7. Grant│                 │               │
       │     access                 │               │
       │          └────────┬────────┘               │
       │                   │                        │
       │                   │ 8. Redirect to         │
       │                   │    /authenticate?code=...&state=...
       │                   └───────────────────────►│
       │                                            │
       │                                            ├─┐ 9. Match state token
       │                                            │ │    Exchange code for tokens
       │                                            │ │    Store credentials
       │                                            │◄┘
       │                                            │
       │  10. Task state: WORKING                   ├─┐ 10. Resume task
       │◄───────────────────────────────────────────┤ │     Retry calendar call
       │                                            │◄┘
       │                                            │
       │  11. Task state: COMPLETED                 │
       │      + Calendar data                       │
       │◄───────────────────────────────────────────┤
       │                                            │
```

## Transport Support

This sample uses JSON-RPC transport for communication between the client and agent. The A2A server agent is configured to use port 11000.

## Code Structure

```
server/src/main/java/com/samples/a2a/
├── MeetingSchedulerAgent.java              # LangChain4j agent interface
├── MeetingSchedulerAgentExecutorProducer.java  # A2A executor with OAuth handling
├── MeetingSchedulerAgentCardProducer.java   # Agent card with dual OAuth schemes
├── GoogleCalendarTools.java                 # Calendar tools requiring OAuth2
├── calendar/
│   └── GoogleCalendarClient.java            # JAX-RS REST client for Google Calendar API
└── oauth/
    ├── AuthContext.java                     # Thread-local context for auth state
    ├── OAuth2StateManager.java              # Manages OAuth state and tokens
    └── OAuthCallbackResource.java           # Handles OAuth callbacks
```

## Troubleshooting

### OAuth Callback Issues

If the OAuth callback isn't working:
1. Verify the redirect URI in Google Console matches `http://localhost:11000/authenticate`
2. Check that the agent is running on port 11000
3. Ensure `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET` are set correctly

### Token Expiration

Tokens automatically refresh using the refresh token. If you encounter auth issues:
- The sample times out after 60 seconds waiting for authentication
- Check server logs for "OAuth flow failed" messages

### Keycloak Connection Issues

If the client can't connect to Keycloak:
1. Ensure Docker/Podman is running
2. Check that port 11001 is available
3. Wait for Keycloak Dev Services to fully start (check logs)

## Google Calendar API Integration

This sample uses **real Google Calendar APIs** via a JAX-RS REST client. The implementation follows the pattern from [quarkus-langchain4j-gemini](https://github.com/sberyozkin/quarkus-langchain4j-gemini):

- **REST Client**: `GoogleCalendarClient.java` defines the Google Calendar API interface
- **Token Management**: Tools manually pass `Authorization` header with bearer token
- **API Calls**: All calendar tools (`GoogleCalendarTools.java`) make direct REST calls to `https://www.googleapis.com/calendar/v3`
- **Automatic Refresh**: Quarkus OIDC Client automatically refreshes expired tokens

### Key Implementation Components

1. **GoogleCalendarClient.java** (`server/src/main/java/com/samples/a2a/calendar/`)
   - JAX-RS REST client interface with `@RegisterRestClient`
   - Methods: `getEvents()`, `addEvent()` accepting `Authorization` header
   - Uses RFC3339 timestamps for all date/time parameters

2. **GoogleCalendarTools.java** (`server/src/main/java/com/samples/a2a/`)
   - Provides two tools: `getEvents()` and `scheduleMeeting()`
   - LLM calculates RFC3339 timestamps based on current date context
   - Makes real API calls in `executeWithAuth()` callback with bearer token
   - Handles RFC3339 timestamp formatting and parsing

## Related Samples

- **[Birthday Planner](https://github.com/a2aproject/a2a-samples/tree/main/samples/python/agents/birthday_planner_adk) (Python/ADK)**: The inspiration for this sample's OAuth2 flow
- **Other A2A Samples**: See `samples/java/agents/` for more examples

## Disclaimer
Important: The sample code provided is for demonstration purposes and illustrates the
mechanics of the Agent-to-Agent (A2A) protocol. When building production applications,
it is critical to treat any agent operating outside of your direct control as a
potentially untrusted entity.

All data received from an external agent—including but not limited to its AgentCard,
messages, artifacts, and task statuses—should be handled as untrusted input. For
example, a malicious agent could provide an AgentCard containing crafted data in its
fields (e.g., description, name, skills.description). If this data is used without
sanitization to construct prompts for a Large Language Model (LLM), it could expose
your application to prompt injection attacks.  Failure to properly validate and
sanitize this data before use can introduce security vulnerabilities into your
application.

Developers are responsible for implementing appropriate security measures, such as
input validation and secure handling of credentials to protect their systems and users.