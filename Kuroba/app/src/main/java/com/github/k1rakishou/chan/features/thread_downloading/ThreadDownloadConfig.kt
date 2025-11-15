package com.github.k1rakishou.chan.features.thread_downloading

/**
 * Configuration constants for thread download retry logic and completion behavior.
 */
object ThreadDownloadConfig {
  /**
   * Maximum retry attempts for non-permanent failures (429 rate limit, network errors, 5xx server errors).
   * After this many attempts, the media file is marked as a permanent failure.
   */
  const val MAX_RETRY_ATTEMPTS = 5

  /**
   * Lower retry count specifically for archived threads.
   * Since archived threads cannot receive new posts, we give up sooner on failing media.
   */
  const val MAX_ARCHIVED_THREAD_RETRY_ATTEMPTS = 3

  /**
   * Maximum download cycles for archived threads before forcing completion.
   * A "cycle" is one execution of the download worker.
   * This is a safety net to prevent infinite loops even if other logic fails.
   */
  const val MAX_ARCHIVED_THREAD_CYCLES = 10

  /**
   * Time to wait before retrying after a network error (milliseconds).
   * Applies to connection errors, timeouts, and other transient network issues.
   */
  const val NETWORK_ERROR_RETRY_DELAY_MS = 5 * 60 * 1000L  // 5 minutes

  /**
   * Time to wait before retrying after a server error (5xx) (milliseconds).
   */
  const val SERVER_ERROR_RETRY_DELAY_MS = 10 * 60 * 1000L  // 10 minutes

  /**
   * Clean up old attempt records after this many days.
   * Prevents the media_download_attempts table from growing indefinitely.
   */
  const val ATTEMPT_RECORD_RETENTION_DAYS = 30

  /**
   * Cutoff time for cleaning up old attempt records.
   */
  fun getAttemptRecordCutoffTime(): Long {
    return System.currentTimeMillis() - (ATTEMPT_RECORD_RETENTION_DAYS * 24 * 60 * 60 * 1000L)
  }
}
