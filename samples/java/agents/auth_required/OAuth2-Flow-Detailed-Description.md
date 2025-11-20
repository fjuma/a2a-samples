# Google Calendar OAuth2 Authentication Flow

This sample implements **Google Calendar OAuth2 authentication** to access the user's calendar data. Here's the complete flow:

## Overview

The Google Calendar auth uses the **OAuth 2.0 Authorization Code Grant** flow, which is appropriate for server-side applications that can securely store client secrets. The flow is "out-of-band" for CLI clients, meaning the user must manually copy/paste the auth URL into a browser.

## Detailed Step-by-Step Flow

### Phase 1: Credentials Check

**1. Tool execution begins** (`GoogleCalendarTools.java:37-87`)

When the LLM calls a calendar tool (e.g., `getEvents()`), the tool needs to access Google Calendar API:

```java
public Uni<String> getEvents(String calendarId, String timeMin, String timeMax) {
  final String userId = AuthContext.getUserId();  // Gets "cli-user"
  return executeWithAuth(userId, accessToken -> {
    // Call Google Calendar API with access token
  });
}
```

**2. Check for stored credentials** (`GoogleCalendarTools.java:163`)

```java
private Uni<String> executeWithAuth(String userId, Function<String, String> operation) {
  if (!stateManager.hasCredentials(userId)) {
    // No credentials found!
    AuthContext.markAuthRequired(userId);
    return Uni.createFrom().item("I need permission to access your Google Calendar...");
  }
  // ... continue with stored credentials
}
```

The `OAuth2StateManager` checks if we have stored tokens for `"cli-user"`:

```java
public boolean hasCredentials(String userId) {
  return userTokens.containsKey(userId);  // ConcurrentHashMap<String, Tokens>
}
```

**3. Mark auth required** (if no credentials exist)

Sets a thread-local flag that the executor will detect:

```java
AuthContext.markAuthRequired(userId);
```

### Phase 2: OAuth Flow Initiation

**4. Executor detects auth required** (`MeetingSchedulerAgentExecutorProducer.java:117-119`)

After the agent call returns:

```java
if (AuthContext.isAuthRequired()) {
  handleOAuthRequired(context, eventQueue, updater, userId, memoryId, question);
  return;  // Don't complete the task yet
}
```

**5. Prepare OAuth request** (`MeetingSchedulerAgentExecutorProducer.java:148-149`)

```java
OAuth2StateManager.AuthDetails authDetails =
    stateManager.prepareAuthRequest(context.getTaskId());
```

This calls `OAuth2StateManager.prepareAuthRequest()`:

```java
public AuthDetails prepareAuthRequest(String taskId) {
  // Generate random state token for CSRF protection
  String state = UUID.randomUUID().toString();

  // Create a CompletableFuture to wait for the OAuth callback
  CompletableFuture<AuthCallback> future = new CompletableFuture<>();

  // Store the future so callback can complete it later
  awaitingAuth.put(state, future);

  // Build Google OAuth authorization URL
  String authUri = String.format(
    "https://accounts.google.com/o/oauth2/auth?" +
    "client_id=%s&" +
    "redirect_uri=%s&" +
    "response_type=code&" +
    "scope=https://www.googleapis.com/auth/calendar&" +
    "state=%s&" +
    "access_type=offline&" +
    "prompt=consent",
    clientId,
    redirectUri,
    state
  );

  return new AuthDetails(state, authUri, future, taskId);
}
```

**Key parameters in the OAuth URL:**
- `client_id`: Your Google OAuth client ID from Google Cloud Console
- `redirect_uri`: `http://localhost:11000/authenticate` (where Google will redirect after auth)
- `response_type=code`: Request an authorization code (not a token directly)
- `scope`: `https://www.googleapis.com/auth/calendar` (full calendar access)
- `state`: Random token for CSRF protection
- `access_type=offline`: Request a refresh token (so we can get new access tokens later)
- `prompt=consent`: Always show consent screen (ensures we get a refresh token)

**6. Update task to AUTH_REQUIRED** (`MeetingSchedulerAgentExecutorProducer.java:152-157`)

```java
String authMessageText =
    "Authorization is required to access your Google Calendar. " +
    "Please visit this URL to authenticate: " + authDetails.authUri;
Message authMessage = createAgentMessage(authMessageText);
updater.updateStatus(TaskState.AUTH_REQUIRED, authMessage);
```

The client receives:
- Task status: `AUTH_REQUIRED`
- Message containing the OAuth URL

**7. Block waiting for callback** (`MeetingSchedulerAgentExecutorProducer.java:163-229`)

```java
stateManager.waitForCallbackAndExchangeToken(authDetails.state, userId, AUTH_TIMEOUT_SECONDS)
  .await().atMost(Duration.ofSeconds(AUTH_TIMEOUT_SECONDS + 10));
```

This **blocks** the execute() method, keeping the event stream open. The blocking is necessary so the client continues receiving status updates.

### Phase 3: User Authentication in Browser

**8. Client displays OAuth URL** (`EventHandlerUtil.java`)

The client detects `AUTH_REQUIRED` and displays:

```
================================================================================
🔐 AUTHENTICATION REQUIRED
================================================================================

Please visit this URL to authenticate: https://accounts.google.com/o/oauth2/auth?...

📋 OAuth URL:
   https://accounts.google.com/o/oauth2/auth?client_id=...&state=abc123...
```

**9. User copies URL to browser**

Manual step - user opens the URL in their web browser.

**10. Google shows OAuth consent screen**

Browser displays:
- App name: "Meeting Scheduler Agent" (from your OAuth client config)
- Requested permissions: "See, edit, share, and permanently delete all calendars..."
- "Allow" or "Deny" buttons

**11. User clicks "Allow"**

User grants permission for the app to access their Google Calendar.

### Phase 4: OAuth Callback & Token Exchange

**12. Google redirects to callback endpoint**

After user clicks "Allow", Google redirects browser to:

```
http://localhost:11000/authenticate?code=4/0AY0e-g7x...&state=abc123xyz...
```

Parameters:
- `code`: Authorization code (single-use, expires in ~10 minutes)
- `state`: The same state token we sent in step 5

**13. Callback endpoint receives request** (`OAuthCallbackResource.java:29-51`)

```java
@GET
@Path("/authenticate")
public String callback(
    @QueryParam("state") String state,
    @QueryParam("code") String code,
    @QueryParam("error") String error) {

  if (error != null) {
    return authError.data("error", error).render();
  }

  if (state == null || code == null) {
    return authError.data("error", "Missing parameters").render();
  }

  try {
    stateManager.handleCallback(state, code);
    return authSuccess.instance().render();  // Show success page in browser
  } catch (Exception e) {
    return authError.data("error", e.getMessage()).render();
  }
}
```

**14. Validate state and retrieve waiting future** (`OAuth2StateManager.java`)

```java
public void handleCallback(String state, String code) {
  // Retrieve the CompletableFuture that's waiting for this callback
  CompletableFuture<AuthCallback> future = awaitingAuth.remove(state);

  if (future == null) {
    throw new IllegalStateException("Invalid or expired state token");
  }

  // Complete the future with the authorization code
  // This unblocks the waiting executor!
  future.complete(new AuthCallback(code, userId, taskId));
}
```

**15. Exchange authorization code for tokens**

The `waitForCallbackAndExchangeToken()` method (which was blocked waiting) now continues:

```java
public Uni<Tokens> waitForCallbackAndExchangeToken(String state, String userId, long timeoutSeconds) {
  CompletableFuture<AuthCallback> future = awaitingAuth.get(state);

  return Uni.createFrom().completionStage(future)
    .onItem().transformToUni(callback -> {
      // Exchange the authorization code for tokens
      OidcClient oidcClient = oidcClients.getClient("google-calendar");

      return oidcClient.getTokens(Map.of(
        "grant_type", "authorization_code",
        "code", callback.code,
        "redirect_uri", REDIRECT_URI
      ));
    })
    .onItem().invoke(tokens -> {
      // Store the tokens for this user
      userTokens.put(userId, tokens);
    });
}
```

The Quarkus OIDC Client makes an HTTP POST to Google's token endpoint:

```http
POST https://oauth2.googleapis.com/token
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code&
code=4/0AY0e-g7x...&
redirect_uri=http://localhost:11000/authenticate&
client_id=YOUR_CLIENT_ID&
client_secret=YOUR_CLIENT_SECRET
```

**16. Google returns tokens**

Google responds with:

```json
{
  "access_token": "ya29.a0AfH6SMB...",
  "refresh_token": "1//0gZ9X...",
  "expires_in": 3599,
  "token_type": "Bearer",
  "scope": "https://www.googleapis.com/auth/calendar"
}
```

- **access_token**: Used to call Google Calendar API (expires in ~1 hour)
- **refresh_token**: Used to get new access tokens (doesn't expire)
- **expires_in**: Seconds until access token expires

**17. Store tokens** (`OAuth2StateManager.java`)

```java
userTokens.put("cli-user", tokens);
```

Stored in-memory in a `ConcurrentHashMap<String, Tokens>`.

### Phase 5: Task Retry with Credentials

**18. CompletableFuture completes, unblocking executor**

The `.await()` call from step 7 now completes, and execution continues.

**19. Update task to WORKING** (`MeetingSchedulerAgentExecutorProducer.java:187-189`)

```java
Message workingMessage = createAgentMessage("Authentication successful, processing...");
updater.updateStatus(TaskState.WORKING, workingMessage);
```

Client receives: "working" status update.

**20. Retry agent call** (`MeetingSchedulerAgentExecutorProducer.java:198-208`)

```java
AuthContext.setTaskId(context.getTaskId());
AuthContext.setUserId(userId);

String retryMemoryId = UUID.randomUUID().toString();
String retryCurrentDate = ZonedDateTime.now().format(...);

String retryResponse = agent.handleRequest(retryMemoryId, question, retryCurrentDate);
```

Fresh memory ID ensures the LLM doesn't see the "I need permission" response from the first attempt.

**21. LLM calls getEvents again**

Same tool call as before: `getEvents("primary", "2025-11-29T14:00:00-05:00", "2025-11-29T15:00:00-05:00")`

**22. Tool executes with credentials** (`GoogleCalendarTools.java:163-188`)

```java
if (!stateManager.hasCredentials(userId)) {
  // ...
}  // FALSE now - has credentials!

return stateManager.getAccessToken(userId)
  .chain(accessToken -> {
    return Uni.createFrom().item(operation.apply(accessToken));
  });
```

**23. Get access token** (`OAuth2StateManager.java`)

```java
public Uni<String> getAccessToken(String userId) {
  Tokens tokens = userTokens.get(userId);

  if (tokens == null) {
    return Uni.createFrom().nullItem();
  }

  // Check if token is expired
  if (tokens.isAccessTokenExpired()) {
    // Refresh the token automatically!
    OidcClient oidcClient = oidcClients.getClient("google-calendar");
    return oidcClient.refreshTokens(tokens.getRefreshToken())
      .onItem().invoke(newTokens -> {
        userTokens.put(userId, newTokens);  // Store refreshed tokens
      })
      .onItem().transform(newTokens -> newTokens.getAccessToken());
  }

  return Uni.createFrom().item(tokens.getAccessToken());
}
```

If the access token is expired, Quarkus automatically calls:

```http
POST https://oauth2.googleapis.com/token
Content-Type: application/x-www-form-urlencoded

grant_type=refresh_token&
refresh_token=1//0gZ9X...&
client_id=YOUR_CLIENT_ID&
client_secret=YOUR_CLIENT_SECRET
```

Google returns a new access token (refresh token remains the same).

### Phase 6: Google Calendar API Call

**24. Call Google Calendar API** (`GoogleCalendarTools.java:44-46`)

```java
GoogleCalendarClient.Events events =
    calendarClient.getEvents(
        "Bearer " + accessToken,  // e.g., "Bearer ya29.a0AfH6SMB..."
        "primary",                 // calendarId
        "2025-11-29T14:00:00-05:00",  // timeMin
        "2025-11-29T15:00:00-05:00",  // timeMax
        100);                      // maxResults
```

**25. REST client makes HTTP request** (`GoogleCalendarClient.java`)

```http
GET https://www.googleapis.com/calendar/v3/calendars/primary/events?timeMin=2025-11-29T14:00:00-05:00&timeMax=2025-11-29T15:00:00-05:00&maxResults=100
Authorization: Bearer ya29.a0AfH6SMB...
```

**26. Google Calendar API responds**

```json
{
  "items": [
    {
      "id": "abc123xyz",
      "summary": "Team Meeting",
      "start": {
        "dateTime": "2025-11-29T14:00:00-05:00",
        "timeZone": "America/New_York"
      },
      "end": {
        "dateTime": "2025-11-29T15:00:00-05:00",
        "timeZone": "America/New_York"
      }
    }
  ]
}
```

**27. Process and format results** (`GoogleCalendarTools.java:48-82`)

```java
if (events.items() == null || events.items().isEmpty()) {
  return "No events found in the specified time range.";
}

StringBuilder result = new StringBuilder("Events:\n\n");
for (GoogleCalendarClient.Event event : events.items()) {
  result.append(event.summary());
  result.append(" - ");
  result.append(formatDateTime(event.start().dateTime()));
  // Calculate duration...
}
return result.toString();
```

Returns to LLM: `"Events:\n\n1. Team Meeting - Friday, November 29 at 2:00 PM (60 minutes)"`

**28. LLM generates natural response**

"You have a Team Meeting at 2pm on Friday, so you're not free at that time."

**29. Task completes** (`MeetingSchedulerAgentExecutorProducer.java:210-213`)

```java
TextPart responsePart = new TextPart(retryResponse, null);
updater.addArtifact(List.of(responsePart), null, null, null);
updater.complete();
```

**30. Client receives completion**

Client sees:
- Status update: "completed"
- Final response with calendar data

---

## Key Security & Design Points

### CSRF Protection via State Token

The `state` parameter prevents cross-site request forgery attacks. The server generates a random UUID, stores it, and validates it matches when the callback arrives.

### Authorization Code vs Access Token

The flow uses authorization code grant (not implicit grant) because:
- More secure - client secret is never exposed to browser
- Provides refresh token for long-term access
- Appropriate for server-side applications

### Refresh Token Flow

- Access tokens expire in ~1 hour
- Refresh tokens don't expire (or expire after months/years)
- Quarkus OIDC Client automatically refreshes access tokens when needed
- No user interaction required for refresh

### In-Memory Storage Limitation

Tokens are stored in `ConcurrentHashMap` - they're lost when server restarts. Production apps should use:
- Database storage
- Encrypted credential vaults
- Redis/session store

### Fixed UserId

This sample uses `"cli-user"` as the userId for simplicity. Production apps should:
- Extract user ID from authentication context
- Support multiple users with separate credentials
- Implement proper user session management

---

This completes the full Google Calendar OAuth2 authentication flow!
