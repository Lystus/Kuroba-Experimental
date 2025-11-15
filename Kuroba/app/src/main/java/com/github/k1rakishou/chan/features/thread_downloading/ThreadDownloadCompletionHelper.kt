package com.github.k1rakishou.chan.features.thread_downloading

import com.github.k1rakishou.chan.core.usecase.DownloadResult
import com.github.k1rakishou.common.AppConstants
import com.github.k1rakishou.core_logger.Logger
import com.github.k1rakishou.fsaf.FileManager
import com.github.k1rakishou.fsaf.file.AbstractFile
import com.github.k1rakishou.model.KurobaDatabase
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import com.github.k1rakishou.model.data.post.ChanPostImage
import com.github.k1rakishou.model.data.thread.ThreadDownload
import com.github.k1rakishou.model.repository.ChanPostImageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Analyzes thread download status and makes smart completion decisions.
 * Handles archived threads with incomplete media by distinguishing between
 * permanent failures and retryable failures.
 */
class ThreadDownloadCompletionHelper(
  private val database: KurobaDatabase,
  private val chanPostImageRepository: ChanPostImageRepository,
  private val fileManager: FileManager,
  private val appConstants: AppConstants
) {

  // Cache for media status analysis (threadDescriptor -> (timestamp, status))
  private val mediaStatusCache = mutableMapOf<ChanDescriptor.ThreadDescriptor, Pair<Long, MediaDownloadStatus>>()
  private val CACHE_TTL_MS = 30_000L // 30 seconds
  
  /**
   * Analyze media download status for a thread.
   * Returns detailed statistics about success/failure counts.
   * Results are cached for 30 seconds to avoid expensive file system scans.
   */
  suspend fun analyzeMediaDownloadStatus(
    threadDescriptor: ChanDescriptor.ThreadDescriptor,
    ownerThreadDatabaseId: Long
  ): MediaDownloadStatus = withContext(Dispatchers.IO) {
    // Check cache first
    val now = System.currentTimeMillis()
    val cached = mediaStatusCache[threadDescriptor]
    if (cached != null) {
      val (timestamp, status) = cached
      if (now - timestamp < CACHE_TTL_MS) {
        // Cache hit - return cached result
        return@withContext status
      }
    }
    
    val chanPostImages = chanPostImageRepository.selectPostImagesByOwnerThreadDatabaseId(ownerThreadDatabaseId)
      .peekError { error -> Logger.e(TAG, "Failed to select images by threadId: $ownerThreadDatabaseId", error) }
      .valueOrNull() ?: emptyList()

    if (chanPostImages.isEmpty()) {
      val emptyStatus = MediaDownloadStatus(
        totalCount = 0,
        successCount = 0,
        permanentFailureCount = 0,
        retryableCount = 0
      )
      mediaStatusCache[threadDescriptor] = now to emptyStatus
      return@withContext emptyStatus
    }

    var totalCount = 0
    var successCount = 0
    var permanentFailureCount = 0
    var retryableCount = 0

    for (postImage in chanPostImages) {
      // Check thumbnail
      val thumbnailUrl = postImage.actualThumbnailUrl
      if (thumbnailUrl != null) {
        totalCount++
        val thumbnailFile = getMediaFile(threadDescriptor, postImage, isThumbnail = true)
        
        if (thumbnailFile != null && fileManager.exists(thumbnailFile) && fileManager.getLength(thumbnailFile) > 0) {
          successCount++
        } else {
          // File missing - check attempt history
          val attempt = database.mediaDownloadAttemptDao().getAttempt(thumbnailUrl.toString())
          
          when {
            attempt?.isPermanentFailure == true -> permanentFailureCount++
            attempt != null && attempt.attemptCount >= ThreadDownloadConfig.MAX_RETRY_ATTEMPTS -> permanentFailureCount++
            else -> retryableCount++
          }
        }
      }

      // Check full image
      val fullImageUrl = postImage.imageUrl
      if (fullImageUrl != null) {
        totalCount++
        val fullImageFile = getMediaFile(threadDescriptor, postImage, isThumbnail = false)
        
        if (fullImageFile != null && fileManager.exists(fullImageFile) && fileManager.getLength(fullImageFile) > 0) {
          successCount++
        } else {
          // File missing - check attempt history
          val attempt = database.mediaDownloadAttemptDao().getAttempt(fullImageUrl.toString())
          
          when {
            attempt?.isPermanentFailure == true -> permanentFailureCount++
            attempt != null && attempt.attemptCount >= ThreadDownloadConfig.MAX_RETRY_ATTEMPTS -> permanentFailureCount++
            else -> retryableCount++
          }
        }
      }
    }

    val status = MediaDownloadStatus(
      totalCount = totalCount,
      successCount = successCount,
      permanentFailureCount = permanentFailureCount,
      retryableCount = retryableCount
    )
    
    // Cache the result
    mediaStatusCache[threadDescriptor] = now to status
    
    return@withContext status
  }
  
  /**
   * Invalidate cache for a specific thread (call after media downloads complete)
   */
  fun invalidateCache(threadDescriptor: ChanDescriptor.ThreadDescriptor) {
    mediaStatusCache.remove(threadDescriptor)
  }
  
  /**
   * Clear all cached media status (call on app restart or memory pressure)
   */
  fun clearCache() {
    mediaStatusCache.clear()
  }

  /**
   * Determine if a thread download should be completed based on its current state.
   * Implements smart completion logic that handles archived threads appropriately.
   */
  suspend fun shouldCompleteDownload(
    threadDownload: ThreadDownload,
    downloadResult: DownloadResult,
    ownerThreadDatabaseId: Long
  ): CompletionDecision {
    val threadDescriptor = threadDownload.threadDescriptor
    val isArchived = downloadResult.archived || downloadResult.closed || downloadResult.deleted

    // CRITICAL: Active threads should NEVER auto-complete, even if all media is downloaded
    // New posts with new media might be added at any time
    if (!isArchived) {
      return CompletionDecision.KeepRunning("Thread still active")
    }

    // If media download is disabled, complete immediately
    if (!threadDownload.downloadMedia) {
      return CompletionDecision.Complete("Media download disabled")
    }

    // Analyze media download status
    val mediaStatus = analyzeMediaDownloadStatus(threadDescriptor, ownerThreadDatabaseId)

    Logger.d(TAG, "shouldCompleteDownload() thread=$threadDescriptor, status=$mediaStatus, " +
      "archived=$isArchived, cyclesSinceProgress=${threadDownload.cyclesSinceProgress}")

    // From this point, we know the thread is archived/closed/deleted
    return when {
      // Case 1: All media downloaded successfully
      mediaStatus.allDownloaded -> {
        CompletionDecision.Complete("All media downloaded (${mediaStatus.successCount}/${mediaStatus.totalCount})")
      }

      // Case 2: Thread has no media at all
      mediaStatus.hasNoMedia -> {
        CompletionDecision.Complete("Thread has no media")
      }

      // Case 3: Has retryable failures (archived threads only)
      mediaStatus.hasRetryableFailures -> {
        val cyclesSinceProgress = threadDownload.cyclesSinceProgress

        if (cyclesSinceProgress >= ThreadDownloadConfig.MAX_ARCHIVED_THREAD_CYCLES) {
          // Max cycles exceeded - force completion
          CompletionDecision.CompleteWithWarning(
            "Max cycles exceeded ($cyclesSinceProgress), " +
            "downloaded ${mediaStatus.successCount}/${mediaStatus.totalCount} media, " +
            "${mediaStatus.retryableCount} still failing"
          )
        } else {
          // Keep trying
          CompletionDecision.KeepRunning(
            "Archived thread has ${mediaStatus.retryableCount} retryable media " +
            "(cycle $cyclesSinceProgress/${ThreadDownloadConfig.MAX_ARCHIVED_THREAD_CYCLES})"
          )
        }
      }

      // Case 4: Only permanent failures left (404, max retries)
      mediaStatus.onlyPermanentFailures -> {
        CompletionDecision.CompleteWithWarning(
          "Downloaded ${mediaStatus.successCount}/${mediaStatus.totalCount} media, " +
          "${mediaStatus.permanentFailureCount} permanently unavailable"
        )
      }

      // Case 5: Mixed state or edge case
      else -> {
        CompletionDecision.KeepRunning(
          "Progress: ${mediaStatus.successCount}/${mediaStatus.totalCount}, " +
          "retryable: ${mediaStatus.retryableCount}, permanent: ${mediaStatus.permanentFailureCount}"
        )
      }
    }
  }

  private fun getMediaFile(
    threadDescriptor: ChanDescriptor.ThreadDescriptor,
    postImage: ChanPostImage,
    isThumbnail: Boolean
  ): AbstractFile? {
    val rootDir = fileManager.fromRawFile(appConstants.threadDownloaderCacheDir)
    val directoryName = formatDirectoryName(threadDescriptor)
    val outputDirectory = fileManager.findFile(rootDir, directoryName) ?: return null

    val fileName = if (isThumbnail) {
      postImage.actualThumbnailUrl?.toString()?.substringAfterLast('/') ?: return null
    } else {
      postImage.imageUrl?.toString()?.substringAfterLast('/') ?: return null
    }

    return fileManager.findFile(outputDirectory, fileName)
  }

  private fun formatDirectoryName(threadDescriptor: ChanDescriptor.ThreadDescriptor): String {
    return "${threadDescriptor.siteName()}_${threadDescriptor.boardCode()}_${threadDescriptor.threadNo}"
  }

  companion object {
    private const val TAG = "ThreadDownloadCompletionHelper"
  }
}
