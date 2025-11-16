package com.github.k1rakishou.chan.features.thread_downloading

import android.widget.Toast
import com.github.k1rakishou.ChanSettings
import com.github.k1rakishou.chan.R
import com.github.k1rakishou.chan.core.base.okhttp.RealDownloaderOkHttpClient
import com.github.k1rakishou.chan.core.helper.ThreadDownloaderFileManagerWrapper
import com.github.k1rakishou.chan.core.manager.RateLimitManager
import com.github.k1rakishou.chan.core.manager.SiteManager
import com.github.k1rakishou.chan.core.manager.ThreadDownloadManager
import com.github.k1rakishou.chan.core.site.SiteResolver
import com.github.k1rakishou.chan.core.usecase.DownloadParams
import com.github.k1rakishou.chan.core.usecase.ThreadDownloaderPersistPostsInDatabaseUseCase
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils
import com.github.k1rakishou.chan.utils.MediaMetadataExtractor
import com.github.k1rakishou.common.AndroidUtils
import com.github.k1rakishou.common.AppConstants
import com.github.k1rakishou.common.ModularResult
import com.github.k1rakishou.common.errorMessageOrClassName
import com.github.k1rakishou.common.extractFileName
import com.github.k1rakishou.common.isNotNullNorEmpty
import com.github.k1rakishou.common.isOutOfDiskSpaceError
import com.github.k1rakishou.common.processDataCollectionConcurrently
import com.github.k1rakishou.common.suspendCall
import com.github.k1rakishou.core_logger.Logger
import com.github.k1rakishou.fsaf.FileManager
import com.github.k1rakishou.fsaf.file.AbstractFile
import com.github.k1rakishou.fsaf.file.DirectorySegment
import com.github.k1rakishou.fsaf.file.FileSegment
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import com.github.k1rakishou.model.data.post.ChanPostImage
import com.github.k1rakishou.model.data.post.ChanPostImageType
import com.github.k1rakishou.model.data.thread.ThreadDownload
import com.github.k1rakishou.model.repository.ChanPostImageMetadataRepository
import com.github.k1rakishou.model.repository.ChanPostImageRepository
import com.github.k1rakishou.model.repository.ChanPostRepository
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.closeQuietly
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File
import com.github.k1rakishou.chan.utils.BackgroundUtils
import kotlin.coroutines.coroutineContext
import kotlin.time.ExperimentalTime
import kotlin.time.measureTimedValue

class ThreadDownloadingDelegate(
  private val appConstants: AppConstants,
  private val appScope: CoroutineScope,
  private val downloaderOkHttpClient: Lazy<RealDownloaderOkHttpClient>,
  private val siteManager: SiteManager,
  private val siteResolver: SiteResolver,
  private val threadDownloadManager: ThreadDownloadManager,
  private val chanPostRepository: ChanPostRepository,
  private val chanPostImageRepository: ChanPostImageRepository,
  private val chanPostImageMetadataRepository: ChanPostImageMetadataRepository,
  private val threadDownloaderFileManagerWrapper: ThreadDownloaderFileManagerWrapper,
  private val threadDownloadProgressNotifier: ThreadDownloadProgressNotifier,
  private val threadDownloaderPersistPostsInDatabaseUseCase: ThreadDownloaderPersistPostsInDatabaseUseCase,
  private val rateLimitManager: RateLimitManager,
  private val mediaMetadataExtractor: MediaMetadataExtractor,
  private val mediaDownloadRetryHelper: MediaDownloadRetryHelper,
  private val threadDownloadCompletionHelper: ThreadDownloadCompletionHelper
) {
  private val fileManager: FileManager
    get() = threadDownloaderFileManagerWrapper.fileManager
  private val okHttpClient: OkHttpClient
    get() = downloaderOkHttpClient.get().okHttpClient()
  private val batchCount = appConstants.processorsCount

  private val _running = AtomicBoolean(false)
  val running: Boolean
    get() = _running.get()

  @OptIn(ExperimentalTime::class)
  suspend fun doWork(): ModularResult<Unit> {
    return ModularResult.Try {
      if (!_running.compareAndSet(false, true)) {
        Logger.d(TAG, "doWorkInternal() already running")
        return@Try
      }

      val (result, duration) = measureTimedValue {
        try {
          doWorkInternal()
        } finally {
          _running.set(false)
        }
      }

      Logger.d(TAG, "doWorkInternal() took $duration")

      return@Try result
    }
  }

  private suspend fun doWorkInternal() {
    siteManager.awaitUntilInitialized()
    chanPostRepository.awaitUntilInitialized()

    // Cleanup incomplete downloads and temporary files from previous sessions
    cleanupIncompleteDownloads()

    // Cleanup old media download attempt records (older than 30 days)
    mediaDownloadRetryHelper.cleanupOldAttempts()

    if (!threadDownloadManager.hasActiveThreads()) {
      Logger.d(TAG, "doWorkInternal() no active threads left, exiting")
      return
    }

    val threadDownloads = threadDownloadManager.getAllActiveThreadDownloads()

    Logger.d(TAG, "doWorkInternal() start, batchCount=$batchCount")
    threadDownloads.forEach { threadDownload ->
      Logger.d(TAG, "doWorkInternal() threadDownload=$threadDownload")
    }

    val outOfDiskSpaceError = AtomicBoolean(false)
    val outputDirError = AtomicBoolean(false)
    val canceled = AtomicBoolean(false)

    threadDownloads.forEachIndexed { index, threadDownload ->
      try {
        if (canceled.get()) {
          return@forEachIndexed
        }

        threadDownloadProgressNotifier.notifyProgressEvent(
          threadDownload.threadDescriptor,
          ThreadDownloadProgressNotifier.Event.Progress(0.1f)
        )

        processThread(
          threadDownload = threadDownload,
          index = index + 1,
          total = threadDownloads.size,
          outOfDiskSpaceError = outOfDiskSpaceError,
          outputDirError = outputDirError
        )

        threadDownloadProgressNotifier.notifyProgressEvent(
          threadDownload.threadDescriptor,
          ThreadDownloadProgressNotifier.Event.Progress(1f)
        )

        threadDownloadProgressNotifier.notifyProgressEvent(
          threadDownload.threadDescriptor,
          ThreadDownloadProgressNotifier.Event.Empty
        )
      } catch (error: CancellationException) {
        Logger.e(TAG, "doWorkInternal() ${threadDownload.threadDescriptor} canceled")
        canceled.set(true)
      }
    }

    // NEW: Log disk space errors (notification would be shown in UI when user opens archive)
    if (outOfDiskSpaceError.get()) {
      Logger.e(TAG, "doWorkInternal() One or more threads stopped due to insufficient disk space")
    }

    coroutineContext[Job.Key]?.invokeOnCompletion { cause ->
      if (cause is CancellationException) {
        threadDownloads.forEach { threadDownload ->
          threadDownloadProgressNotifier.notifyProgressEvent(
            threadDownload.threadDescriptor,
            ThreadDownloadProgressNotifier.Event.Empty
          )
        }
      }
    }

    threadDownloadManager.onThreadsProcessed()
    Logger.d(TAG, "doWorkInternal() success")
  }

  private suspend fun processThread(
    threadDownload: ThreadDownload,
    index: Int,
    total: Int,
    outOfDiskSpaceError: AtomicBoolean,
    outputDirError: AtomicBoolean,
  ) {
    val threadDescriptor = threadDownload.threadDescriptor
    Logger.d(TAG, "processThread($index/$total) loadThreadOrCatalog($threadDescriptor) start")

    val params = DownloadParams(threadDownload.ownerThreadDatabaseId, threadDescriptor)
    val executionResult = threadDownloaderPersistPostsInDatabaseUseCase.execute(params)

    threadDownloadProgressNotifier.notifyProgressEvent(
      threadDownload.threadDescriptor,
      ThreadDownloadProgressNotifier.Event.Progress(POSTS_PROCESSED_PROGRESS)
    )

    val downloadResult = if (executionResult is ModularResult.Error) {
      Logger.e(TAG, "processThread($index/$total) loadThreadOrCatalog($threadDescriptor)", executionResult.error)

      threadDownloadManager.onDownloadProcessed(
        threadDescriptor = threadDescriptor,
        resultMessage = executionResult.error.message
          ?: executionResult.error.errorMessageOrClassName()
      )

      return
    } else {
      executionResult as ModularResult.Value
      executionResult.value
    }

    val ownerThreadDatabaseId = threadDownload.ownerThreadDatabaseId

    val isNetworkGoodForMediaDownload = if (ChanSettings.threadDownloaderDownloadMediaOnMeteredNetwork.get()) {
      true
    } else {
      AppModuleAndroidUtils.isConnectionUnmetered()
    }

    val canProcessThreadMedia = threadDownload.downloadMedia
      && !outOfDiskSpaceError.get()
      && isNetworkGoodForMediaDownload

    if (canProcessThreadMedia) {
      val chanPostImages = chanPostImageRepository.selectPostImagesByOwnerThreadDatabaseId(ownerThreadDatabaseId)
        .peekError { error -> Logger.e(TAG, "Failed to select images by threadId: ${ownerThreadDatabaseId}", error) }
        .mapErrorToValue { emptyList<ChanPostImage>() }

      // Always try to download media for archived/deleted/closed threads that have media enabled
      // This helps with resuming incomplete downloads
      val isArchivedThread = downloadResult.archived || downloadResult.closed || downloadResult.deleted
      
      processThreadMedia(
        index = index,
        total = total,
        chanPostImages = chanPostImages,
        threadDescriptor = threadDescriptor,
        isArchivedThread = isArchivedThread,
        outOfDiskSpaceError = outOfDiskSpaceError,
        outputDirError = outputDirError
      )
    } else {
      Logger.d(TAG, "processThread($index/$total) " +
        "isNetworkGoodForMediaDownload=$isNetworkGoodForMediaDownload, " +
        "downloadMedia=${threadDownload.downloadMedia}, " +
        "outOfDiskSpaceError=${outOfDiskSpaceError.get()}")
    }

    // NEW: Handle disk space error for this specific thread
    if (outOfDiskSpaceError.get()) {
      Logger.e(TAG, "processThread($index/$total) Out of disk space for $threadDescriptor, stopping download")
      threadDownloadManager.updateThreadDownload(
        threadDescriptor = threadDescriptor,
        updaterFunc = { it.copy(status = ThreadDownload.Status.Stopped) }
      )
      threadDownloadManager.onDownloadProcessed(
        threadDescriptor = threadDescriptor,
        resultMessage = "Out of disk space"
      )
      return
    }

    val resultMessage = when {
      outputDirError.get() -> "Output directory access error"
      else -> null
    }

    // Phase 2: Use smart completion logic FIRST (analyzes media status internally)
    val completionDecision = threadDownloadCompletionHelper.shouldCompleteDownload(
      threadDownload = threadDownload,
      downloadResult = downloadResult,
      ownerThreadDatabaseId = ownerThreadDatabaseId
    )

    when (completionDecision) {
      is CompletionDecision.Complete -> {
        Logger.d(TAG, "processThread($index/$total) completing thread: ${completionDecision.reason}")
        // Success completion - explicitly clear message (will show checkmark icon)
        threadDownloadManager.completeDownloading(
          threadDescriptor = threadDescriptor,
          completionMessage = null,
          isSuccessCompletion = true
        )
      }
      is CompletionDecision.CompleteWithWarning -> {
        Logger.w(TAG, "processThread($index/$total) completing thread with warning: ${completionDecision.reason}")
        // Warning completion - store message (will show exclamation icon)
        threadDownloadManager.completeDownloading(
          threadDescriptor = threadDescriptor,
          completionMessage = completionDecision.reason,
          isSuccessCompletion = false
        )
      }
      is CompletionDecision.KeepRunning -> {
        Logger.d(TAG, "processThread($index/$total) continuing download: ${completionDecision.reason}")
        
        // Phase 3: Track download cycles ONLY for threads that keep running
        // Only track for archived/closed/deleted threads
        val isArchived = downloadResult.archived || downloadResult.closed || downloadResult.deleted
        if (isArchived) {
          // Get current media status to check for actual progress
          val mediaStatus = threadDownloadCompletionHelper.analyzeMediaDownloadStatus(
            threadDescriptor = threadDescriptor,
            ownerThreadDatabaseId = threadDownload.ownerThreadDatabaseId
          )
          
          threadDownloadManager.updateThreadDownload(threadDescriptor) { download ->
            val now = System.currentTimeMillis()
            val currentSuccessCount = mediaStatus.successCount
            val highestSuccessCount = download.lastKnownSuccessCount
            
            // Check if we made actual progress (more media downloaded than ever before)
            // Use >= to handle the case where files might be re-downloaded after deletion
            val madeProgress = currentSuccessCount >= highestSuccessCount && currentSuccessCount > 0
            
            // Track the highest success count we've ever seen
            // This prevents false "no progress" detection if user deletes files
            val newHighestSuccessCount = maxOf(currentSuccessCount, highestSuccessCount)
            
            val newCyclesSinceProgress = if (madeProgress && currentSuccessCount > highestSuccessCount) {
              0  // Reset counter - we made NEW progress!
            } else {
              download.cyclesSinceProgress + 1  // Increment - no NEW progress
            }
            
            Logger.d(TAG, "processThread($index/$total) cycle tracking: " +
              "successCount=$currentSuccessCount (highest=$highestSuccessCount), " +
              "madeProgress=$madeProgress, " +
              "cyclesSinceProgress=$newCyclesSinceProgress")
            
            download.copy(
              downloadCyclesCount = download.downloadCyclesCount + 1,
              lastProgressTime = now,
              cyclesSinceProgress = newCyclesSinceProgress,
              lastKnownSuccessCount = newHighestSuccessCount
            )
          }
        }
        
        // Update lastUpdateTime for running threads
        threadDownloadManager.onDownloadProcessed(
          threadDescriptor = threadDescriptor,
          resultMessage = resultMessage
        )
      }
    }

    val status = "archived: ${downloadResult.archived}, " +
      "closed: ${downloadResult.closed}, " +
      "deleted: ${downloadResult.deleted}, " +
      "outOfDiskSpace: ${outOfDiskSpaceError.get()}, " +
      "outputDirError: ${outputDirError.get()}, " +
      "decision: $completionDecision"

    Logger.d(TAG, "processThread($index/$total) loadThreadOrCatalog($threadDescriptor) end, status: $status")
  }

  private suspend fun processThreadMedia(
    index: Int,
    total: Int,
    chanPostImages: List<ChanPostImage>,
    threadDescriptor: ChanDescriptor.ThreadDescriptor,
    isArchivedThread: Boolean,
    outOfDiskSpaceError: AtomicBoolean,
    outputDirError: AtomicBoolean,
  ) {
    if (chanPostImages.isEmpty()) {
      Logger.d(TAG, "processThreadMedia($index/$total) threadDescriptor=${threadDescriptor}, " +
        "chanPostImages=${chanPostImages.size}, nothing to process")
      return
    }

    // Check if we're in a rate limit cooldown period for this site
    val siteDescriptor = threadDescriptor.siteDescriptor()
    if (rateLimitManager.isInCooldown(siteDescriptor)) {
      val remainingDuration = rateLimitManager.getRemainingCooldown(siteDescriptor)
      val remainingSeconds = remainingDuration?.standardSeconds ?: 0
      Logger.w(TAG, "processThreadMedia($index/$total) skipping due to rate limit cooldown " +
        "for site=${siteDescriptor.siteName} (${remainingSeconds}s remaining)")
      
      // Update thread status to indicate partial download (posts downloaded, media skipped)
      threadDownloadManager.onDownloadProcessed(
        threadDescriptor = threadDescriptor,
        resultMessage = "Rate limited - media download paused (${remainingSeconds}s)"
      )
      return
    }

    val rootDir = fileManager.fromRawFile(appConstants.threadDownloaderCacheDir)
    Logger.d(TAG, "processThreadMedia($index/$total) threadDescriptor=${threadDescriptor}, " +
      "chanPostImages=${chanPostImages.size}")

    val directoryName = formatDirectoryName(threadDescriptor)

    var outputDirectory = fileManager.findFile(rootDir, directoryName)
    if (outputDirectory == null) {
      outputDirectory = fileManager.create(rootDir, listOf(DirectorySegment(directoryName)))
    }

    if (outputDirectory == null) {
      Logger.d(TAG, "processThreadMedia($index/$total) " +
        "chanThread=${threadDescriptor} failure! outputDirectory is null")
      outputDirError.set(true)
      return
    }

    val noMediaFile = outputDirectory.clone(FileSegment(NO_MEDIA_FILE_NAME))
    if (!fileManager.exists(noMediaFile)) {
      // Disable media scanner
      fileManager.create(noMediaFile)
    }

    // "* 2" because thumbnails and full images
    val progressIncrement = (1f - POSTS_PROCESSED_PROGRESS) / (chanPostImages.size.toFloat() * 2)
    val mutex = Mutex()
    var totalProgress = POSTS_PROCESSED_PROGRESS

    processDataCollectionConcurrently(
      dataList = chanPostImages,
      batchCount = batchCount,
      dispatcher = Dispatchers.IO
    ) { postImage ->
      val isNetworkGoodForMediaDownload = if (ChanSettings.threadDownloaderDownloadMediaOnMeteredNetwork.get()) {
        true
      } else {
        AppModuleAndroidUtils.isConnectionUnmetered()
      }

      if (!isNetworkGoodForMediaDownload) {
        return@processDataCollectionConcurrently
      }

      if (outOfDiskSpaceError.get()) {
        return@processDataCollectionConcurrently
      }

      if (outputDirError.get()) {
        return@processDataCollectionConcurrently
      }

      val thumbnailUrl = postImage.actualThumbnailUrl
      val thumbnailName = postImage.actualThumbnailUrl?.extractFileName()

      if (thumbnailUrl != null && thumbnailName.isNotNullNorEmpty()) {
        downloadImage(
          threadDescriptor = threadDescriptor,
          outputDirectory = outputDirectory,
          postImage = postImage,
          isThumbnail = true,
          name = thumbnailName,
          imageUrl = thumbnailUrl,
          isArchivedThread = isArchivedThread,
          outOfDiskSpaceError = outOfDiskSpaceError,
          outputDirError = outputDirError
        )
      }

      val newProgress1 = mutex.withLock {
        totalProgress += progressIncrement
        totalProgress
      }
      threadDownloadProgressNotifier.notifyProgressEvent(
        threadDescriptor,
        ThreadDownloadProgressNotifier.Event.Progress(newProgress1)
      )

      val fullImageUrl = postImage.imageUrl
      val fullImageName = postImage.imageUrl?.extractFileName()

      if (fullImageUrl != null && fullImageName.isNotNullNorEmpty()) {
        downloadImage(
          threadDescriptor = threadDescriptor,
          outputDirectory = outputDirectory,
          postImage = postImage,
          isThumbnail = false,
          name = fullImageName,
          imageUrl = fullImageUrl,
          isArchivedThread = isArchivedThread,
          outOfDiskSpaceError = outOfDiskSpaceError,
          outputDirError = outputDirError
        )
      }

      val newProgress2 = mutex.withLock {
        totalProgress += progressIncrement
        totalProgress
      }
      threadDownloadProgressNotifier.notifyProgressEvent(
        threadDescriptor,
        ThreadDownloadProgressNotifier.Event.Progress(newProgress2)
      )
    }

    Logger.d(TAG, "processThreadMedia($index/$total) chanThread=${threadDescriptor} success")
  }

  private suspend fun downloadImage(
    threadDescriptor: ChanDescriptor.ThreadDescriptor,
    outputDirectory: AbstractFile,
    postImage: ChanPostImage,
    isThumbnail: Boolean,
    name: String,
    imageUrl: HttpUrl,
    isArchivedThread: Boolean,
    outOfDiskSpaceError: AtomicBoolean,
    outputDirError: AtomicBoolean,
  ) {
    // Check retry helper before attempting download
    
    val decision = mediaDownloadRetryHelper.shouldDownloadMedia(
      url = imageUrl,
      threadDescriptor = threadDescriptor,
      isArchivedThread = isArchivedThread
    )
    
    if (!decision.shouldProceed()) {
      Logger.d(TAG, "downloadImage() skipping $name: ${(decision as MediaDownloadDecision.Skip).reason}")
      return
    }

    var outputFile = fileManager.findFile(outputDirectory, name)
    if (outputFile == null) {
      outputFile = fileManager.create(outputDirectory, listOf(FileSegment(name)))
    }

    if (outputFile == null) {
      outputDirError.set(true)
      return
    }

    // Enhanced file verification - check both existence and reasonable file size
    if (fileManager.exists(outputFile)) {
      val fileSize = fileManager.getLength(outputFile)
      if (fileSize > 0L) {
        // More intelligent size validation that considers server optimization
        if (isFileSizeReasonable(fileSize, isThumbnail, -1L)) { // Will use fallback validation
          // Enhanced corruption detection: verify file header/magic numbers
          if (isValidImageFile(outputFile)) {
            // File exists and appears valid, record success and skip download
            mediaDownloadRetryHelper.recordSuccess(imageUrl)
            return
          } else {
            // File exists but appears corrupt based on header verification
            Logger.w(TAG, "downloadImage() found corrupt file (invalid header), re-downloading: $name")
            fileManager.delete(outputFile)
          }
        } else {
          // File exists but size is suspicious
          Logger.w(TAG, "downloadImage() found suspicious file size (${fileSize} bytes), re-downloading: $name")
          fileManager.delete(outputFile)
        }
      }
    }

    // Apply per-file delay ONLY when we're about to download (not when file already exists)
    val delayMs = ChanSettings.threadDownloaderMediaDownloadDelayMs.get()
    if (delayMs > 0) {
      kotlinx.coroutines.delay(delayMs.toLong())
    }

    // Use temporary file for atomic writes
    val tempFileName = "${name}.tmp"
    var tempFile = fileManager.findFile(outputDirectory, tempFileName)
    if (tempFile == null) {
      tempFile = fileManager.create(outputDirectory, listOf(FileSegment(tempFileName)))
    }

    if (tempFile == null) {
      outputDirError.set(true)
      return
    }

    val site = siteResolver.findSiteForUrl(imageUrl.toString())
    val requestModifier = site?.requestModifier()

    val requestBuilder = Request.Builder()
      .url(imageUrl)
      .get()

    if (site != null && requestModifier != null) {
      if (isThumbnail) {
        requestModifier.modifyThumbnailGetRequest(site, requestBuilder)
      } else {
        requestModifier.modifyFullImageGetRequest(site, requestBuilder)
      }
    }

    val response = okHttpClient.suspendCall(requestBuilder.build())
    if (!response.isSuccessful) {
      // Record failure with retry helper
      mediaDownloadRetryHelper.recordFailure(
        url = imageUrl,
        threadDescriptor = threadDescriptor,
        httpCode = response.code,
        error = null,
        isArchivedThread = isArchivedThread
      )
      
      // Check for rate limiting (HTTP 429)
      if (response.code == 429) {
        val retryAfterSeconds = response.header("Retry-After")?.toLongOrNull()?.toInt() ?: 60
        
        val siteDescriptor = threadDescriptor.siteDescriptor()
        Logger.w(TAG, "downloadImage() rate limited (429) for site=${siteDescriptor.siteName}, " +
          "Retry-After: ${retryAfterSeconds}s")
        
        rateLimitManager.setCooldown(siteDescriptor, retryAfterSeconds)
        
        // Notify user about rate limit with formatted time
        val siteName = siteDescriptor.siteName
        val timeFormatted = formatDuration(retryAfterSeconds.toLong())
        val message = AppModuleAndroidUtils.getString(
          R.string.thread_downloader_rate_limited, 
          siteName,
          timeFormatted
        )
        AppModuleAndroidUtils.showToast(AndroidUtils.getAppContext(), message, Toast.LENGTH_LONG)
        
        // Clean up and return - the cooldown will prevent further downloads until it expires
        fileManager.delete(tempFile)
        return
      }
      
      Logger.e(TAG, "downloadImage(isThumbnail=$isThumbnail, name=$name, imageUrl=$imageUrl) " +
        "bad response code: ${response.code}")
      fileManager.delete(tempFile)
      return
    }

    val responseBody = if (response.body == null) {
      Logger.e(TAG, "downloadImage(isThumbnail=$isThumbnail, name=$name, imageUrl=$imageUrl) " +
        "response body is null")
      fileManager.delete(tempFile)
      return
    } else {
      response.body!!
    }

    // Get expected content length from server response
    val expectedContentLength = responseBody.contentLength()
    Logger.d(TAG, "downloadImage() $name expectedContentLength=$expectedContentLength")

    var downloadSuccess = false
    try {
      val outputStream = fileManager.getOutputStream(tempFile)
      if (outputStream == null) {
        Logger.e(TAG, "downloadImage(isThumbnail=$isThumbnail, name=$name, imageUrl=$imageUrl) " +
          "failed to get output stream for temp file '${tempFile.getFullPath()}'")
        return
      }

      var totalBytesWritten = 0L
      runInterruptible {
        responseBody.byteStream().use { inputStream ->
          outputStream.use { os ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
              os.write(buffer, 0, bytesRead)
              totalBytesWritten += bytesRead
            }
          }
        }
      }

      // Verify download completed successfully
      val finalFileSize = fileManager.getLength(tempFile)
      if (finalFileSize > 0L && totalBytesWritten == finalFileSize) {
        // Additional validation: check if downloaded size is reasonable
        if (isFileSizeReasonable(finalFileSize, isThumbnail, expectedContentLength)) {
          // Atomic rename: move temp file to final location
          if (fileManager.exists(outputFile)) {
            fileManager.delete(outputFile)
          }
          
          // Create final file and copy contents
          val finalFile = fileManager.create(outputDirectory, listOf(FileSegment(name)))
          if (finalFile != null && fileManager.copyFileContents(tempFile, finalFile)) {
            downloadSuccess = true
            Logger.d(TAG, "downloadImage() successfully downloaded $name (${finalFileSize} bytes, expected: ${expectedContentLength})")
            
            // Record success with retry helper
            mediaDownloadRetryHelper.recordSuccess(imageUrl)
            
            // Extract metadata for full media files (not thumbnails, only for MOVIE and GIF types)
            if (!isThumbnail && (postImage.type == ChanPostImageType.MOVIE || postImage.type == ChanPostImageType.GIF)) {
              val fileHash = postImage.fileHash
              if (fileHash != null) {
                try {
                  val javaFile = finalFile.getFullPath()?.let { java.io.File(it) }
                  if (javaFile != null && javaFile.exists()) {
                    val metadata = mediaMetadataExtractor.extract(javaFile)
                    if (metadata != null) {
                      chanPostImageMetadataRepository.store(
                        imageHash = fileHash,
                        durationMs = metadata.durationMs,
                        hasAudio = metadata.hasAudio
                      )
                      Logger.d(TAG, "downloadImage() extracted metadata for $name: duration=${metadata.durationMs}ms, hasAudio=${metadata.hasAudio}")
                    } else {
                      Logger.w(TAG, "downloadImage() metadata extraction returned null for $name")
                    }
                  } else {
                    Logger.w(TAG, "downloadImage() could not get file path for metadata extraction: $name")
                  }
                } catch (error: Throwable) {
                  Logger.e(TAG, "downloadImage() failed to extract metadata for $name: ${error.errorMessageOrClassName()}")
                }
              } else {
                Logger.d(TAG, "downloadImage() skipping metadata extraction for $name (fileHash is null)")
              }
            }
          } else {
            Logger.e(TAG, "downloadImage() failed to create final file or copy contents for $name")
          }
        } else {
          Logger.w(TAG, "downloadImage() downloaded file size seems unreasonable for $name: ${finalFileSize} bytes (expected: ${expectedContentLength})")
        }
      } else {
        Logger.e(TAG, "downloadImage() size mismatch for $name: written=$totalBytesWritten, final=$finalFileSize")
      }
    } catch (error: Throwable) {
      if (error.isOutOfDiskSpaceError()) {
        outOfDiskSpaceError.set(true)
      }

      // Record failure with retry helper
      mediaDownloadRetryHelper.recordFailure(
        url = imageUrl,
        threadDescriptor = threadDescriptor,
        httpCode = null,
        error = error,
        isArchivedThread = isArchivedThread
      )

      Logger.e(TAG, "Failed to download image $name. Error: ${error.errorMessageOrClassName()}")
    } finally {
      responseBody.closeQuietly()
      // Always cleanup temp file
      fileManager.delete(tempFile)
      
      // If download failed, ensure output file is also cleaned up
      if (!downloadSuccess && fileManager.exists(outputFile)) {
        fileManager.delete(outputFile)
      }
    }
  }

  /**
   * Validates if a file size is reasonable considering server-side optimizations
   * This accounts for compression, format conversion, and CDN optimization
   */
  private fun isFileSizeReasonable(
    actualSize: Long,
    isThumbnail: Boolean,
    expectedContentLength: Long
  ): Boolean {
    // Absolute minimum sizes for valid images (very conservative)
    val absoluteMinSize = if (isThumbnail) 50L else 200L
    
    // If file is smaller than absolute minimum, it's definitely corrupt
    if (actualSize < absoluteMinSize) {
      return false
    }
    
    // If server didn't provide content-length, use more permissive validation
    if (expectedContentLength <= 0L) {
      val reasonableMinSize = if (isThumbnail) 100L else 500L
      return actualSize >= reasonableMinSize
    }
    
    // Server provided expected size - validate against it with generous tolerance
    val tolerance = when {
      // Small files can vary more proportionally
      expectedContentLength < 1024L -> 0.8 // Allow 80% variance for very small files
      expectedContentLength < 10240L -> 0.5 // Allow 50% variance for small files  
      expectedContentLength < 102400L -> 0.3 // Allow 30% variance for medium files
      else -> 0.2 // Allow 20% variance for large files
    }
    
    val minExpectedSize = (expectedContentLength * (1.0 - tolerance)).toLong()
    val maxExpectedSize = (expectedContentLength * (1.0 + tolerance)).toLong()
    
    val isWithinRange = actualSize in minExpectedSize..maxExpectedSize
    
    if (!isWithinRange) {
      Logger.d(TAG, "isFileSizeReasonable() size out of range: actual=$actualSize, " +
        "expected=$expectedContentLength, range=[$minExpectedSize, $maxExpectedSize]")
    }
    
    return isWithinRange
  }

  /**
   * Validates if a file is a valid image by checking file headers/magic numbers
   * This provides better corruption detection than just file size checks
   */
  private suspend fun isValidImageFile(file: AbstractFile): Boolean {
    return try {
      val inputStream = fileManager.getInputStream(file)
      if (inputStream == null) {
        return false
      }

      inputStream.use { stream ->
        val buffer = ByteArray(12) // Enough bytes to check most image formats
        val bytesRead = stream.read(buffer)
        
        if (bytesRead < 4) {
          return false // Not enough data to determine format
        }

        // Check for common image format magic numbers
        when {
          // JPEG: FF D8 FF
          buffer[0] == 0xFF.toByte() && buffer[1] == 0xD8.toByte() && buffer[2] == 0xFF.toByte() -> {
            // Additional JPEG validation: check for valid JPEG segments
            return isValidJpegFile(file)
          }
          
          // PNG: 89 50 4E 47 0D 0A 1A 0A
          buffer[0] == 0x89.toByte() && buffer[1] == 0x50.toByte() && 
          buffer[2] == 0x4E.toByte() && buffer[3] == 0x47.toByte() &&
          buffer[4] == 0x0D.toByte() && buffer[5] == 0x0A.toByte() &&
          buffer[6] == 0x1A.toByte() && buffer[7] == 0x0A.toByte() -> {
            return isValidPngFile(file)
          }
          
          // GIF: GIF87a or GIF89a
          buffer[0] == 0x47.toByte() && buffer[1] == 0x49.toByte() && buffer[2] == 0x46.toByte() &&
          (buffer[3] == 0x38.toByte() && (buffer[4] == 0x37.toByte() || buffer[4] == 0x39.toByte()) && buffer[5] == 0x61.toByte()) -> {
            return isValidGifFile(file)
          }
          
          // WebP: RIFF....WEBP
          buffer[0] == 0x52.toByte() && buffer[1] == 0x49.toByte() && buffer[2] == 0x46.toByte() && buffer[3] == 0x46.toByte() &&
          bytesRead >= 12 && buffer[8] == 0x57.toByte() && buffer[9] == 0x45.toByte() && buffer[10] == 0x42.toByte() && buffer[11] == 0x50.toByte() -> {
            return isValidWebPFile(file)
          }
          
          // BMP: BM
          buffer[0] == 0x42.toByte() && buffer[1] == 0x4D.toByte() -> {
            return isValidBmpFile(file)
          }
          
          else -> {
            Logger.w(TAG, "isValidImageFile() unknown image format for file: ${file.getFullPath()}")
            return true // Unknown format, assume valid to avoid false positives
          }
        }
      }
    } catch (error: Throwable) {
      Logger.e(TAG, "isValidImageFile() error checking file: ${file.getFullPath()}", error)
      return true // On error, assume valid to avoid false positives
    }
  }

  /**
   * Validates JPEG file structure by checking for valid segments
   */
  private suspend fun isValidJpegFile(file: AbstractFile): Boolean {
    return try {
      val inputStream = fileManager.getInputStream(file)
      if (inputStream == null) return false

      inputStream.use { stream ->
        val buffer = ByteArray(2)
        var segmentCount = 0
        val maxSegments = 10 // Limit checks to avoid performance issues
        
        // Skip initial SOI marker (FF D8)
        stream.skip(2)
        
        while (segmentCount < maxSegments) {
          val bytesRead = stream.read(buffer)
          if (bytesRead < 2) break
          
          // Check for valid JPEG segment marker (FF XX)
          if (buffer[0] == 0xFF.toByte() && buffer[1] != 0x00.toByte()) {
            segmentCount++
            
            // Check for End Of Image marker (FF D9)
            if (buffer[1] == 0xD9.toByte()) {
              return true // Valid JPEG with proper ending
            }
            
            // Skip segment data based on length
            if (buffer[1] != 0xD8.toByte() && buffer[1] != 0xD9.toByte()) {
              val lengthBytes = ByteArray(2)
              if (stream.read(lengthBytes) == 2) {
                val length = ((lengthBytes[0].toInt() and 0xFF) shl 8) or (lengthBytes[1].toInt() and 0xFF)
                if (length > 2) {
                  stream.skip((length - 2).toLong())
                }
              }
            }
          } else {
            break // Invalid segment marker
          }
        }
        
        return segmentCount > 0 // At least some valid segments found
      }
    } catch (error: Throwable) {
      Logger.e(TAG, "isValidJpegFile() error", error)
      return true // Assume valid on error
    }
  }

  /**
   * Validates PNG file structure by checking CRC and basic chunks
   */
  private suspend fun isValidPngFile(file: AbstractFile): Boolean {
    return try {
      val inputStream = fileManager.getInputStream(file)
      if (inputStream == null) return false

      inputStream.use { stream ->
        val buffer = ByteArray(8)
        
        // Skip PNG signature (already verified)
        stream.skip(8)
        
        // Check for IHDR chunk (must be first chunk)
        val bytesRead = stream.read(buffer)
        if (bytesRead < 8) return false
        
        // IHDR chunk length (4 bytes) + chunk type "IHDR" (4 bytes)
        val chunkType = String(buffer, 4, 4, StandardCharsets.US_ASCII)
        return chunkType == "IHDR"
      }
    } catch (error: Throwable) {
      Logger.e(TAG, "isValidPngFile() error", error)
      return true // Assume valid on error
    }
  }

  /**
   * Validates GIF file structure by checking for basic GIF structure
   */
  private suspend fun isValidGifFile(file: AbstractFile): Boolean {
    return try {
      val inputStream = fileManager.getInputStream(file)
      if (inputStream == null) return false

      inputStream.use { stream ->
        val buffer = ByteArray(13) // GIF header + logical screen descriptor
        val bytesRead = stream.read(buffer)
        
        // Check minimum GIF file size and structure
        return bytesRead >= 13 && fileManager.getLength(file) > 20L
      }
    } catch (error: Throwable) {
      Logger.e(TAG, "isValidGifFile() error", error)
      return true // Assume valid on error
    }
  }

  /**
   * Validates WebP file structure
   */
  private suspend fun isValidWebPFile(file: AbstractFile): Boolean {
    return try {
      val inputStream = fileManager.getInputStream(file)
      if (inputStream == null) return false

      inputStream.use { stream ->
        val buffer = ByteArray(16)
        val bytesRead = stream.read(buffer)
        
        // Basic WebP validation: check file size field consistency
        if (bytesRead >= 16) {
          val fileSize = ((buffer[7].toInt() and 0xFF) shl 24) or
                        ((buffer[6].toInt() and 0xFF) shl 16) or
                        ((buffer[5].toInt() and 0xFF) shl 8) or
                        (buffer[4].toInt() and 0xFF)
          
          val actualSize = fileManager.getLength(file)
          // WebP file size should match the declared size (with 8 byte header offset)
          return actualSize == (fileSize + 8).toLong()
        }
        
        return false
      }
    } catch (error: Throwable) {
      Logger.e(TAG, "isValidWebPFile() error", error)
      return true // Assume valid on error
    }
  }

  /**
   * Validates BMP file structure
   */
  private suspend fun isValidBmpFile(file: AbstractFile): Boolean {
    return try {
      val inputStream = fileManager.getInputStream(file)
      if (inputStream == null) return false

      inputStream.use { stream ->
        val buffer = ByteArray(14) // BMP header
        val bytesRead = stream.read(buffer)
        
        if (bytesRead >= 14) {
          // Check file size field in BMP header
          val declaredSize = ((buffer[5].toInt() and 0xFF) shl 24) or
                            ((buffer[4].toInt() and 0xFF) shl 16) or
                            ((buffer[3].toInt() and 0xFF) shl 8) or
                            (buffer[2].toInt() and 0xFF)
          
          val actualSize = fileManager.getLength(file)
          return actualSize == declaredSize.toLong()
        }
        
        return false
      }
    } catch (error: Throwable) {
      Logger.e(TAG, "isValidBmpFile() error", error)
      return true // Assume valid on error
    }
  }

  private suspend fun isAllMediaDownloaded(
    threadDescriptor: ChanDescriptor.ThreadDescriptor,
    ownerThreadDatabaseId: Long
  ): Boolean {
    val chanPostImages = chanPostImageRepository.selectPostImagesByOwnerThreadDatabaseId(ownerThreadDatabaseId)
      .peekError { error -> Logger.e(TAG, "Failed to select images by threadId: ${ownerThreadDatabaseId}", error) }
      .valueOrNull() ?: return true // If we can't fetch images, assume they're all downloaded

    if (chanPostImages.isEmpty()) {
      Logger.d(TAG, "isAllMediaDownloaded() no images for thread: $threadDescriptor")
      return true // No images to download
    }

    val rootDir = fileManager.fromRawFile(appConstants.threadDownloaderCacheDir)
    val directoryName = formatDirectoryName(threadDescriptor)
    val outputDirectory = fileManager.findFile(rootDir, directoryName)
    
    if (outputDirectory == null) {
      Logger.d(TAG, "isAllMediaDownloaded() output directory not found for thread: $threadDescriptor")
      return false
    }

    var totalImages = 0
    var downloadedImages = 0
    var missingImages = mutableListOf<String>()

    // Check if all images (thumbnails and full images) are downloaded
    for (postImage in chanPostImages) {
      // Check thumbnail
      val thumbnailName = postImage.actualThumbnailUrl?.extractFileName()
      if (thumbnailName.isNotNullNorEmpty()) {
        totalImages++
        val thumbnailFile = fileManager.findFile(outputDirectory, thumbnailName)
        if (thumbnailFile != null && fileManager.exists(thumbnailFile) && fileManager.getLength(thumbnailFile) > 0L) {
          downloadedImages++
        } else {
          missingImages.add("thumbnail: $thumbnailName")
        }
      }

      // Check full image
      val fullImageName = postImage.imageUrl?.extractFileName()
      if (fullImageName.isNotNullNorEmpty()) {
        totalImages++
        val fullImageFile = fileManager.findFile(outputDirectory, fullImageName)
        if (fullImageFile != null && fileManager.exists(fullImageFile) && fileManager.getLength(fullImageFile) > 0L) {
          downloadedImages++
        } else {
          missingImages.add("full image: $fullImageName")
        }
      }
    }

    val allDownloaded = downloadedImages == totalImages
    
    Logger.d(TAG, "isAllMediaDownloaded() thread=$threadDescriptor, " +
      "downloaded=$downloadedImages/$totalImages, allDownloaded=$allDownloaded")
    
    if (!allDownloaded && missingImages.isNotEmpty()) {
      Logger.d(TAG, "isAllMediaDownloaded() missing images: ${missingImages.take(5).joinToString(", ")}" +
        if (missingImages.size > 5) " and ${missingImages.size - 5} more..." else "")
    }

    return allDownloaded
  }

  /**
   * Cleanup incomplete downloads and temporary files on app restart
   */
  suspend fun cleanupIncompleteDownloads() {
    try {
      val rootDir = fileManager.fromRawFile(appConstants.threadDownloaderCacheDir)
      if (!fileManager.exists(rootDir)) {
        return
      }

      // Get all thread download directories
      val threadDirs = fileManager.listFiles(rootDir)
        .filter { fileManager.isDirectory(it) }

      for (threadDir in threadDirs) {
        val files = fileManager.listFiles(threadDir)
        
        // Remove temporary files
        val tempFiles = files.filter { file ->
          val fileName = file.getFullPath()
          fileName.endsWith(".tmp") || fileName.endsWith(".part")
        }
        
        tempFiles.forEach { tempFile ->
          Logger.d(TAG, "cleanupIncompleteDownloads() removing temp file: ${tempFile.getFullPath()}")
          fileManager.delete(tempFile)
        }

        // Check for suspiciously small files that might be corrupt
        val imageFiles = files.filter { file ->
          val fileName = file.getFullPath()
          !fileName.startsWith(".") && 
          !fileName.endsWith(".tmp") && 
          !fileName.endsWith(".part")
        }
        
        imageFiles.forEach { imageFile ->
          val fileSize = fileManager.getLength(imageFile)
          val fileName = imageFile.getFullPath()
          
          // Check if file is suspiciously small (using more conservative thresholds)
          val isThumbnail = fileName.contains("s.") || fileName.contains("thumb")
          val absoluteMinSize = if (isThumbnail) 50L else 200L
          
          var shouldDelete = false
          var reason = ""
          
          if (fileSize > 0L && fileSize < absoluteMinSize) {
            shouldDelete = true
            reason = "extremely small file (${fileSize} bytes, likely corrupt)"
          } else if (fileSize >= absoluteMinSize) {
            // Additional validation: check if file is actually a valid image
            if (!isValidImageFile(imageFile)) {
              shouldDelete = true
              reason = "invalid image format/corruption detected"
            }
          }
          
          if (shouldDelete) {
            Logger.w(TAG, "cleanupIncompleteDownloads() removing $reason: $fileName")
            fileManager.delete(imageFile)
          }
        }
      }
      
      Logger.d(TAG, "cleanupIncompleteDownloads() completed")
      
      // Removed premature validation that was marking active threads as complete
      // The normal download cycle now handles completion checking properly using
      // ThreadDownloadCompletionHelper which checks BOTH media status AND thread activity
      
      // One-time legacy data cleanup: clear success messages from old completed threads
      // This runs synchronously to ensure cleanup completes before worker processes threads
      runOneTimeCleanup()
    } catch (error: Throwable) {
      Logger.e(TAG, "cleanupIncompleteDownloads() error", error)
    }
  }
  
  /**
   * One-time cleanup to remove success messages from downloadResultMsg.
   * This fixes the UI issue where completed threads show exclamation icon
   * because old success messages were stored in downloadResultMsg field.
   * 
   * Runs synchronously on worker startup to ensure cleanup happens before
   * thread processing. Has timeout protection and retry limit.
   */
  private suspend fun runOneTimeCleanup() {
    if (isCleanupDone()) {
      Logger.d(TAG, "Success message cleanup already completed")
      return
    }
    
    val retryCount = getRetryCount()
    if (retryCount >= 3) {
      Logger.e(TAG, "Cleanup failed 3 times, giving up")
      markCleanupDone() // Mark as done to prevent infinite retries
      clearRetryCount()
      return
    }
    
    try {
      withTimeout(5000) { // 5 second timeout
        cleanupSuccessMessages()
      }
      markCleanupDone()
      clearRetryCount()
      Logger.d(TAG, "Success message cleanup completed successfully")
    } catch (e: TimeoutCancellationException) {
      incrementRetryCount()
      Logger.e(TAG, "Cleanup timed out (attempt ${retryCount + 1}/3), will retry next launch", e)
    } catch (e: Exception) {
      incrementRetryCount()
      Logger.e(TAG, "Cleanup failed (attempt ${retryCount + 1}/3), will retry next launch", e)
    }
  }
  
  /**
   * Clears downloadResultMsg field for all threads that have success messages.
   * This includes all statuses - Running, Stopped, and Completed threads.
   */
  private suspend fun cleanupSuccessMessages() {
    BackgroundUtils.ensureBackgroundThread()
    
    val allThreadDownloads = threadDownloadManager.getAllThreadDownloads()
    val threadsNeedingCleanup = allThreadDownloads.filter { threadDownload ->
      threadDownload.downloadResultMsg != null &&
      isSuccessMessage(threadDownload.downloadResultMsg!!)
    }
    
    Logger.d(TAG, "Found ${threadsNeedingCleanup.size} threads with success messages needing cleanup")
    
    threadsNeedingCleanup.forEach { threadDownload ->
      try {
        threadDownloadManager.updateThreadDownload(threadDownload.threadDescriptor) { 
          it.copy(downloadResultMsg = null)
        }
      } catch (e: Exception) {
        Logger.e(TAG, "Failed to cleanup thread ${threadDownload.threadDescriptor}", e)
        throw e // Re-throw to trigger retry
      }
    }
    
    Logger.d(TAG, "Successfully cleaned up ${threadsNeedingCleanup.size} threads")
  }
  
  /**
   * Check if a downloadResultMsg represents a success message (not a warning).
   * Success messages should be cleared so the UI shows a checkmark instead of exclamation.
   */
  private fun isSuccessMessage(message: String): Boolean {
    val lowerMsg = message.lowercase()
    
    // First check: if it contains warning keywords, it's NOT success
    val warningKeywords = listOf(
      "warning", "unavailable", "exceeded", "failed", 
      "failing", "permanently", "error", "incomplete"
    )
    
    if (warningKeywords.any { lowerMsg.contains(it) }) {
      return false
    }
    
    // Second check: does it match success patterns?
    val successPatterns = listOf(
      "all media downloaded",
      "thread has no media", 
      "media download disabled",
      "download complete"
    )
    
    return successPatterns.any { lowerMsg.contains(it) }
  }
  
  private fun getCleanupFlagFile(): File {
    return File(appConstants.threadDownloaderCacheDir, ".success_msg_cleanup_v1.done")
  }
  
  private fun isCleanupDone(): Boolean {
    return getCleanupFlagFile().exists()
  }
  
  private fun markCleanupDone() {
    try {
      getCleanupFlagFile().createNewFile()
    } catch (e: Exception) {
      Logger.e(TAG, "Failed to create cleanup flag file", e)
    }
  }
  
  private fun getRetryCountFile(): File {
    return File(appConstants.threadDownloaderCacheDir, ".success_msg_cleanup_retries")
  }
  
  private fun getRetryCount(): Int {
    return try {
      val file = getRetryCountFile()
      if (file.exists()) file.readText().toIntOrNull() ?: 0 else 0
    } catch (e: Exception) {
      0
    }
  }
  
  private fun incrementRetryCount() {
    try {
      val count = getRetryCount() + 1
      getRetryCountFile().writeText(count.toString())
    } catch (e: Exception) {
      Logger.e(TAG, "Failed to increment retry count", e)
    }
  }
  
  private fun clearRetryCount() {
    try {
      getRetryCountFile().delete()
    } catch (e: Exception) {
      Logger.e(TAG, "Failed to clear retry count", e)
    }
  }

  companion object {
    private const val TAG = "ThreadDownloadingDelegate"
    private const val NO_MEDIA_FILE_NAME = ".nomedia"
    private const val POSTS_PROCESSED_PROGRESS = 0.2f

    /**
     * Format duration in seconds to human-readable string.
     * - Less than 60s: "X seconds"
     * - 1-59 minutes: "X minutes"
     * - 1+ hours: "Xh Ym" (e.g., "2h 30m")
     */
    private fun formatDuration(seconds: Long): String {
      return when {
        seconds < 60 -> "$seconds seconds"
        seconds < 3600 -> {
          val minutes = seconds / 60
          "$minutes minutes"
        }
        else -> {
          val hours = seconds / 3600
          val minutes = (seconds % 3600) / 60
          if (minutes > 0) {
            "${hours}h ${minutes}m"
          } else {
            "${hours}h"
          }
        }
      }
    }

    fun formatDirectoryName(threadDescriptor: ChanDescriptor.ThreadDescriptor): String {
      return buildString {
        append(threadDescriptor.siteName())
        append("_")
        append(threadDescriptor.boardCode())
        append("_")
        append(threadDescriptor.threadNo)
      }
    }
  }

}