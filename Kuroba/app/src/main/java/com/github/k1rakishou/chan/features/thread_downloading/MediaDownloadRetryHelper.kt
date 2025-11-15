package com.github.k1rakishou.chan.features.thread_downloading

import com.github.k1rakishou.chan.core.base.okhttp.RealProxiedOkHttpClient
import com.github.k1rakishou.chan.core.manager.RateLimitManager
import com.github.k1rakishou.common.suspendCall
import com.github.k1rakishou.core_logger.Logger
import com.github.k1rakishou.model.KurobaDatabase
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import com.github.k1rakishou.model.entity.download.MediaDownloadAttemptEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.HttpUrl

/**
 * Manages retry tracking for media file downloads.
 * Tracks attempt counts, error types, and determines if files should be retried or skipped.
 */
class MediaDownloadRetryHelper(
  private val database: KurobaDatabase,
  private val proxiedOkHttpClient: RealProxiedOkHttpClient,
  private val rateLimitManager: RateLimitManager
) {

  /**
   * Check if a media file should be downloaded, considering retry history.
   * Returns null if the file should be skipped (permanent failure, max retries, or rate limited).
   */
  suspend fun shouldDownloadMedia(
    url: HttpUrl,
    threadDescriptor: ChanDescriptor.ThreadDescriptor,
    isArchivedThread: Boolean
  ): MediaDownloadDecision {
    val attempt = database.mediaDownloadAttemptDao().getAttempt(url.toString())

    // Skip if permanent failure already recorded
    if (attempt?.isPermanentFailure == true) {
      Logger.d(TAG, "shouldDownloadMedia() skipping permanently failed media: $url (reason: ${attempt.lastErrorType})")
      return MediaDownloadDecision.Skip("Permanent failure: ${attempt.lastErrorType}")
    }

    // Check if max retries exceeded
    val maxRetries = if (isArchivedThread) {
      ThreadDownloadConfig.MAX_ARCHIVED_THREAD_RETRY_ATTEMPTS
    } else {
      ThreadDownloadConfig.MAX_RETRY_ATTEMPTS
    }

    if (attempt != null && attempt.attemptCount >= maxRetries) {
      Logger.w(TAG, "shouldDownloadMedia() max retry attempts exceeded for $url (${attempt.attemptCount}/$maxRetries)")
      
      // Mark as permanent failure if not already marked
      if (!attempt.isPermanentFailure) {
        database.mediaDownloadAttemptDao().insertOrUpdate(
          attempt.copy(
            isPermanentFailure = true,
            lastErrorType = "max_retries_exceeded"
          )
        )
      }
      
      return MediaDownloadDecision.Skip("Max retries exceeded (${attempt.attemptCount})")
    }

    // Check if we should wait for rate limit cooldown
    val retryTimestamp = attempt?.retryAfterTimestamp
    if (retryTimestamp != null) {
      val now = System.currentTimeMillis()
      if (now < retryTimestamp) {
        val waitSeconds = (retryTimestamp - now) / 1000
        Logger.d(TAG, "shouldDownloadMedia() rate limit cooldown active, waiting ${waitSeconds}s for $url")
        return MediaDownloadDecision.Skip("Rate limited, retry after ${waitSeconds}s")
      }
    }

    return MediaDownloadDecision.Proceed(attempt?.attemptCount ?: 0)
  }

  /**
   * Record a successful download and clear any attempt history.
   */
  suspend fun recordSuccess(url: HttpUrl) {
    database.mediaDownloadAttemptDao().deleteByUrl(url.toString())
  }

  /**
   * Record a failed download attempt with error details.
   */
  suspend fun recordFailure(
    url: HttpUrl,
    threadDescriptor: ChanDescriptor.ThreadDescriptor,
    httpCode: Int?,
    error: Throwable?,
    isArchivedThread: Boolean
  ) {
    val existingAttempt = database.mediaDownloadAttemptDao().getAttempt(url.toString())
    val attemptCount = (existingAttempt?.attemptCount ?: 0) + 1

    val (errorType, isPermanent, retryAfterMs) = when {
      httpCode == 404 || httpCode == 410 -> {
        // Permanent failures
        Triple("http_$httpCode", true, null)
      }
      httpCode == 429 -> {
        // Rate limited - will be handled by RateLimitManager at site level
        // Just record the attempt
        val retryAfter = ThreadDownloadConfig.NETWORK_ERROR_RETRY_DELAY_MS
        Triple("http_429", false, System.currentTimeMillis() + retryAfter)
      }
      httpCode != null && httpCode >= 500 -> {
        // Server errors - temporary
        val retryAfter = ThreadDownloadConfig.SERVER_ERROR_RETRY_DELAY_MS
        Triple("http_$httpCode", false, System.currentTimeMillis() + retryAfter)
      }
      error != null -> {
        // Network errors - temporary
        val retryAfter = ThreadDownloadConfig.NETWORK_ERROR_RETRY_DELAY_MS
        Triple("network_error", false, System.currentTimeMillis() + retryAfter)
      }
      else -> {
        // Unknown error
        Triple("unknown", false, null)
      }
    }

    val newAttempt = MediaDownloadAttemptEntity(
      imageUrl = url.toString(),
      threadDescriptor = threadDescriptor.serializeToString(),
      attemptCount = attemptCount,
      lastAttemptTime = System.currentTimeMillis(),
      lastErrorType = errorType,
      isPermanentFailure = isPermanent,
      retryAfterTimestamp = retryAfterMs
    )

    database.mediaDownloadAttemptDao().insertOrUpdate(newAttempt)

    Logger.d(TAG, "recordFailure() url=$url, httpCode=$httpCode, errorType=$errorType, " +
      "isPermanent=$isPermanent, attemptCount=$attemptCount")
  }

  /**
   * Clean up old attempt records (older than ATTEMPT_RECORD_RETENTION_DAYS).
   */
  suspend fun cleanupOldAttempts() {
    val cutoffTime = ThreadDownloadConfig.getAttemptRecordCutoffTime()
    database.mediaDownloadAttemptDao().deleteOldAttempts(cutoffTime)
  }

  /**
   * Clean up all attempt records for a specific thread.
   */
  suspend fun cleanupThreadAttempts(threadDescriptor: ChanDescriptor.ThreadDescriptor) {
    database.mediaDownloadAttemptDao().deleteByThread(threadDescriptor.serializeToString())
  }

  companion object {
    private const val TAG = "MediaDownloadRetryHelper"
  }
}

/**
 * Decision about whether to proceed with downloading a media file.
 */
sealed class MediaDownloadDecision {
  data class Proceed(val attemptCount: Int) : MediaDownloadDecision()
  data class Skip(val reason: String) : MediaDownloadDecision()

  fun shouldProceed(): Boolean = this is Proceed
}
