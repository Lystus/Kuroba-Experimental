package com.github.k1rakishou.chan.features.thread_downloading

/**
 * Analysis of media download status for a thread.
 * Used to determine if a thread should be marked as completed.
 */
data class MediaDownloadStatus(
  val totalCount: Int,
  val successCount: Int,
  val permanentFailureCount: Int,
  val retryableCount: Int
) {
  /**
   * All media files have been successfully downloaded.
   */
  val allDownloaded: Boolean
    get() = totalCount > 0 && successCount == totalCount

  /**
   * Some media files have retryable failures (rate limited, network errors, server errors).
   */
  val hasRetryableFailures: Boolean
    get() = retryableCount > 0

  /**
   * All failures are permanent (404, max retries exceeded).
   * No point in continuing to retry.
   */
  val onlyPermanentFailures: Boolean
    get() = retryableCount == 0 && permanentFailureCount > 0

  /**
   * Thread has no media at all.
   */
  val hasNoMedia: Boolean
    get() = totalCount == 0

  override fun toString(): String {
    return "MediaDownloadStatus(total=$totalCount, success=$successCount, permanent=$permanentFailureCount, retryable=$retryableCount)"
  }
}
