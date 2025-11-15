package com.github.k1rakishou.chan.features.thread_downloading

/**
 * Result of attempting to download a single media file (thumbnail or full image).
 */
sealed class MediaDownloadResult {
  /**
   * File was successfully downloaded and saved.
   */
  object Success : MediaDownloadResult()

  /**
   * Download failed with a permanent error that won't be recovered by retrying.
   * Examples: HTTP 404 (not found), HTTP 410 (gone)
   */
  data class PermanentFailure(val reason: String) : MediaDownloadResult()

  /**
   * Download failed after exceeding maximum retry attempts.
   * The error may have been recoverable initially but too many attempts were made.
   */
  data class MaxRetriesExceeded(val attemptCount: Int) : MediaDownloadResult()

  /**
   * Download was rate limited (HTTP 429).
   * Should retry after the specified cooldown period.
   */
  data class RateLimited(val retryAfterSeconds: Long) : MediaDownloadResult()

  /**
   * Download failed due to a network error (connection timeout, DNS failure, etc.).
   * May be recoverable on retry.
   */
  data class NetworkError(val error: Throwable) : MediaDownloadResult()

  /**
   * Download failed with an HTTP error code other than 404/429.
   * Examples: 500 (server error), 503 (service unavailable)
   */
  data class HttpError(val code: Int) : MediaDownloadResult()

  /**
   * Download was skipped because the file already exists.
   */
  object AlreadyExists : MediaDownloadResult()

  fun isSuccess(): Boolean = this is Success || this is AlreadyExists
  fun isPermanent(): Boolean = this is PermanentFailure || this is MaxRetriesExceeded
  fun isRetryable(): Boolean = this is RateLimited || this is NetworkError || this is HttpError
}
