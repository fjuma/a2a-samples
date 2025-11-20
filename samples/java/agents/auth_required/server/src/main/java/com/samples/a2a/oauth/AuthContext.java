package com.samples.a2a.oauth;

/**
 * Thread-local context for tracking authentication requirements during tool execution.
 * This allows tools to signal that OAuth is needed without throwing exceptions,
 * which would be caught by LangChain4j's tool executor.
 */
public final class AuthContext {

  private AuthContext() {
  }

  /** Thread-local storage for auth requirement state. */
  private static final ThreadLocal<AuthRequirement> AUTH_REQUIREMENT =
      ThreadLocal.withInitial(() -> null);

  /** Thread-local storage for current task ID. */
  private static final ThreadLocal<String> TASK_ID = ThreadLocal.withInitial(() -> null);

  /** Thread-local storage for current user ID. */
  private static final ThreadLocal<String> USER_ID = ThreadLocal.withInitial(() -> null);

  /**
   * Set the current task ID for this thread.
   *
   * @param taskId the task ID
   */
  public static void setTaskId(final String taskId) {
    TASK_ID.set(taskId);
  }

  /**
   * Get the current task ID for this thread.
   *
   * @return the task ID, or null if not set
   */
  public static String getTaskId() {
    return TASK_ID.get();
  }

  /**
   * Set the current user ID for this thread.
   *
   * @param userId the user ID
   */
  public static void setUserId(final String userId) {
    USER_ID.set(userId);
  }

  /**
   * Get the current user ID for this thread.
   *
   * @return the user ID, or null if not set
   */
  public static String getUserId() {
    return USER_ID.get();
  }

  /**
   * Mark that authentication is required for the current task.
   *
   * @param userId the user ID that needs authentication
   */
  public static void markAuthRequired(final String userId) {
    AUTH_REQUIREMENT.set(new AuthRequirement(userId, TASK_ID.get()));
  }

  /**
   * Check if authentication is required for the current task.
   *
   * @return true if auth is required, false otherwise
   */
  public static boolean isAuthRequired() {
    return AUTH_REQUIREMENT.get() != null;
  }

  /**
   * Get the auth requirement details.
   *
   * @return the auth requirement, or null if not set
   */
  public static AuthRequirement getAuthRequirement() {
    return AUTH_REQUIREMENT.get();
  }

  /**
   * Clear the auth requirement and task ID for this thread.
   * Should be called after handling the auth requirement or when task completes.
   */
  public static void clear() {
    AUTH_REQUIREMENT.remove();
    TASK_ID.remove();
  }

  /** Details about an authentication requirement. */
  public static class AuthRequirement {
    private final String userId;
    private final String taskId;

    /**
     * Constructor.
     *
     * @param userIdParam the user ID
     * @param taskIdParam the task ID
     */
    public AuthRequirement(final String userIdParam, final String taskIdParam) {
      this.userId = userIdParam;
      this.taskId = taskIdParam;
    }

    /**
     * Get the user ID.
     *
     * @return the user ID
     */
    public String getUserId() {
      return userId;
    }

    /**
     * Get the task ID.
     *
     * @return the task ID
     */
    public String getTaskId() {
      return taskId;
    }
  }
}
