package com.github.k1rakishou.chan.core.usecase

import com.github.k1rakishou.chan.core.manager.ThreadDownloadManager
import com.github.k1rakishou.chan.utils.MediaMetadataExtractor
import com.github.k1rakishou.common.AppConstants
import com.github.k1rakishou.core_logger.Logger
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import com.github.k1rakishou.model.data.post.ChanPostImage
import com.github.k1rakishou.model.repository.ChanPostImageMetadataRepository
import com.github.k1rakishou.model.data.options.PostsToReloadOptions
import com.github.k1rakishou.model.repository.ChanPostRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ExtractAllMetadataUseCase(
    private val appConstants: AppConstants,
    private val threadDownloadManager: ThreadDownloadManager,
    private val chanPostRepository: ChanPostRepository,
    private val mediaMetadataExtractor: MediaMetadataExtractor,
    private val chanPostImageMetadataRepository: ChanPostImageMetadataRepository
) {

    suspend fun execute(onProgress: (current: Int, total: Int) -> Unit): Result = withContext(Dispatchers.IO) {
        Logger.d(TAG, "execute() start")

        // STEP 1: Take snapshot - freeze the world
        val snapshots = buildThreadSnapshots()
        Logger.d(TAG, "execute() captured ${snapshots.size} thread snapshots")

        if (snapshots.isEmpty()) {
            Logger.d(TAG, "execute() no threads to process")
            return@withContext Result(
                successCount = 0,
                skippedCount = 0,
                errorCount = 0
            )
        }

        // Count total files to process
        val totalFiles = snapshots.sumOf { it.postImages.size }
        var processedFiles = 0
        var successCount = 0
        var skippedCount = 0
        var errorCount = 0

        Logger.d(TAG, "execute() processing $totalFiles files across ${snapshots.size} threads")

        // STEP 2: Process snapshot (immune to changes)
        snapshots.forEach { snapshot ->
            snapshot.postImages.forEach { postImage ->
                try {
                    val extracted = extractMetadataForImage(
                        threadDescriptor = snapshot.threadDescriptor,
                        postImage = postImage
                    )

                    when (extracted) {
                        ExtractionResult.Success -> successCount++
                        ExtractionResult.AlreadyExists -> skippedCount++
                        ExtractionResult.FileNotFound -> skippedCount++
                        ExtractionResult.Error -> errorCount++
                    }
                } catch (e: Exception) {
                    Logger.e(TAG, "execute() error extracting metadata", e)
                    errorCount++
                }

                processedFiles++
                withContext(Dispatchers.Main) {
                    onProgress(processedFiles, totalFiles)
                }
            }
        }

        Logger.d(TAG, "execute() complete: success=$successCount, skipped=$skippedCount, errors=$errorCount")

        return@withContext Result(
            successCount = successCount,
            skippedCount = skippedCount,
            errorCount = errorCount
        )
    }

    private suspend fun buildThreadSnapshots(): List<ThreadSnapshot> {
        val threadDownloads = threadDownloadManager.getAllThreadDownloads()
            .filter { it.status.isCompleted() }
            .filter { it.downloadMedia }

        return threadDownloads.mapNotNull { threadDownload ->
            val posts = chanPostRepository.getThreadPostBuilders(
                threadDescriptor = threadDownload.threadDescriptor,
                postsToReloadOptions = PostsToReloadOptions.ReloadAll
            ).valueOrNull() ?: emptyList()

            val postImages = posts.flatMap { postBuilder -> 
                postBuilder.postImages
            }

            if (postImages.isEmpty()) {
                null
            } else {
                ThreadSnapshot(
                    threadDescriptor = threadDownload.threadDescriptor,
                    postImages = postImages.toList() // Immutable copy
                )
            }
        }
    }

    private suspend fun extractMetadataForImage(
        threadDescriptor: ChanDescriptor.ThreadDescriptor,
        postImage: ChanPostImage
    ): ExtractionResult {
        // Find file on disk first
        val imageUrl = postImage.imageUrl ?: return ExtractionResult.FileNotFound
        val mediaFile = threadDownloadManager.findDownloadedFile(
            httpUrl = imageUrl,
            threadDescriptor = threadDescriptor
        )

        if (mediaFile == null) {
            Logger.d(TAG, "extractMetadataForImage() file not found: ${postImage.imageUrl}")
            return ExtractionResult.FileNotFound
        }

        val javaFile = mediaFile.getFullPath()?.let { File(it) }
        if (javaFile == null || !javaFile.exists()) {
            Logger.d(TAG, "extractMetadataForImage() file path invalid or doesn't exist")
            return ExtractionResult.FileNotFound
        }

        // Use URL as hash if fileHash is null (for imported threads)
        val fileHash = postImage.fileHash ?: imageUrl.toString()
        
        Logger.d(TAG, "extractMetadataForImage() using hash: ${fileHash.take(50)}")
        Logger.d(TAG, "extractMetadataForImage() extracting metadata from: ${javaFile.name}")

        // Extract metadata
        return try {
            val metadata = mediaMetadataExtractor.extract(javaFile)
            if (metadata != null) {
                chanPostImageMetadataRepository.store(
                    imageHash = fileHash,
                    durationMs = metadata.durationMs,
                    hasAudio = metadata.hasAudio
                )
                Logger.d(TAG, "extractMetadataForImage() success: duration=${metadata.durationMs}ms, hasAudio=${metadata.hasAudio}")
                ExtractionResult.Success
            } else {
                Logger.w(TAG, "extractMetadataForImage() extractor returned null")
                ExtractionResult.Error
            }
        } catch (e: Exception) {
            Logger.e(TAG, "extractMetadataForImage() failed", e)
            ExtractionResult.Error
        }
    }

    private data class ThreadSnapshot(
        val threadDescriptor: ChanDescriptor.ThreadDescriptor,
        val postImages: List<ChanPostImage>
    )

    private enum class ExtractionResult {
        Success,
        AlreadyExists,
        FileNotFound,
        Error
    }

    data class Result(
        val successCount: Int,
        val skippedCount: Int,
        val errorCount: Int
    )

    companion object {
        private const val TAG = "ExtractAllMetadataUseCase"
    }
}
