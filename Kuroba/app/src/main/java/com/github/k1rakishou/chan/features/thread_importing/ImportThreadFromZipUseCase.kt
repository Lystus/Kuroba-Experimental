/*
 * KurobaEx - *chan browser https://github.com/K1rakishou/Kuroba-Experimental/
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.github.k1rakishou.chan.features.thread_importing

import com.github.k1rakishou.chan.core.base.okhttp.RealDownloaderOkHttpClient
import com.github.k1rakishou.chan.core.manager.BoardManager
import com.github.k1rakishou.chan.core.manager.ChanThreadManager
import com.github.k1rakishou.chan.core.manager.SiteManager
import com.github.k1rakishou.chan.core.manager.ThreadDownloadManager
import com.github.k1rakishou.chan.core.site.loader.internal.usecase.ParsePostsV1UseCase
import com.github.k1rakishou.chan.core.usecase.ISuspendUseCase
import com.github.k1rakishou.chan.core.usecase.ThreadDataPreloader
import com.github.k1rakishou.chan.utils.BackgroundUtils
import com.github.k1rakishou.common.AppConstants
import com.github.k1rakishou.common.ModularResult
import com.github.k1rakishou.core_logger.Logger
import org.joda.time.DateTime
import com.github.k1rakishou.fsaf.FileManager
import com.github.k1rakishou.fsaf.file.ExternalFile
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import com.github.k1rakishou.model.data.descriptor.PostDescriptor
import com.github.k1rakishou.model.data.descriptor.SiteDescriptor
import com.github.k1rakishou.model.data.options.ChanCacheOptions
import com.github.k1rakishou.model.data.options.ChanCacheUpdateOptions
import com.github.k1rakishou.model.data.post.ChanOriginalPost
import com.github.k1rakishou.model.data.post.ChanPost
import com.github.k1rakishou.model.data.post.ChanPostBuilder
import com.github.k1rakishou.model.data.post.ChanPostImage
import com.github.k1rakishou.model.data.post.ChanPostImageBuilder
import com.github.k1rakishou.model.data.thread.ChanThread
import com.github.k1rakishou.model.repository.ChanPostImageRepository
import com.github.k1rakishou.model.repository.ChanPostRepository
import com.github.k1rakishou.model.source.cache.thread.ChanThreadsCache
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.IOException
import javax.inject.Inject

class ImportThreadFromZipUseCase @Inject constructor(
    private val fileManager: FileManager,
    private val threadImportParser: ThreadImportParser,
    private val siteManager: SiteManager,
    private val boardManager: BoardManager,
    private val chanThreadManager: ChanThreadManager,
    private val chanThreadsCache: ChanThreadsCache,
    private val threadDownloadManager: ThreadDownloadManager,
    private val chanPostRepository: ChanPostRepository,
    private val chanPostImageRepository: ChanPostImageRepository,
    private val appConstants: AppConstants,
    private val okHttpClient: RealDownloaderOkHttpClient,
    private val threadDataPreloader: ThreadDataPreloader,
    private val parsePostsV1UseCase: ParsePostsV1UseCase
) : ISuspendUseCase<ImportThreadFromZipUseCase.Params, ModularResult<ChanDescriptor.ThreadDescriptor>> {

    override suspend fun execute(parameter: Params): ModularResult<ChanDescriptor.ThreadDescriptor> {
        BackgroundUtils.ensureBackgroundThread()
        Logger.d(TAG, "ImportThreadFromZipUseCase.execute() called with fake HTTP URL fix")
        
        return ModularResult.Try {
            val zipFile = parameter.zipFile
            val onProgress = parameter.onProgress
            
            Logger.d(TAG, "Starting thread import from ZIP: ${zipFile.getFullPath()}")
            
            onProgress(ProgressUpdate("Parsing ZIP file...", 0.1f))
            
            // Parse the ZIP file
            Logger.d(TAG, "Calling threadImportParser.parseHtmlFromZip()...")
            
            // Try to get the actual filename from the file URI if it's a content URI
            val fullPath = zipFile.getFullPath()
            Logger.d(TAG, "Full ZIP path: $fullPath")
            
            val filename = if (fullPath != null && fullPath.startsWith("content://")) {
                // It's a content URI - need to query ContentResolver for actual filename
                Logger.d(TAG, "Detected content URI, attempting to extract filename")
                val uri = zipFile.getUri()
                Logger.d(TAG, "Got URI from getUri(): $uri")
                if (uri != null) {
                    val extractedName = tryExtractFileNameFromUri(uri, parameter.appContext)
                    Logger.d(TAG, "Extracted filename from URI: $extractedName")
                    extractedName ?: "unknown.zip"
                } else {
                    Logger.w(TAG, "getUri() returned null, cannot extract filename")
                    "unknown.zip"
                }
            } else {
                java.net.URLDecoder.decode(fullPath ?: "unknown.zip", "UTF-8")
                    .substringAfterLast("/")
                    .substringAfterLast("\\")
            }
            
            Logger.d(TAG, "Final extracted filename: $filename")
            val importData = threadImportParser.parseHtmlFromZip(zipFile, filename).unwrap()
            val threadDescriptor = importData.threadDescriptor
            
            Logger.d(TAG, "Successfully parsed thread descriptor: $threadDescriptor")
            Logger.d(TAG, "Found ${importData.posts.size} posts and ${importData.mediaFiles.size} media files")
            
            onProgress(ProgressUpdate("Validating thread data...", 0.2f))
            
            // Validate that we have the necessary site and board
            val siteDescriptor = SiteDescriptor.create(threadDescriptor.siteName())
            Logger.d(TAG, "Looking for site: ${threadDescriptor.siteName()}")
            val site = siteManager.bySiteDescriptor(siteDescriptor)
                ?: throw IllegalArgumentException("Site not found: ${threadDescriptor.siteName()}")
            
            Logger.d(TAG, "Found site: ${site.name()}")
            Logger.d(TAG, "Looking for board: ${threadDescriptor.boardDescriptor()}")
            val board = boardManager.byBoardDescriptor(threadDescriptor.boardDescriptor())
                ?: throw IllegalArgumentException("Board not found: ${threadDescriptor.boardDescriptor()}")
            
            Logger.d(TAG, "Found board: ${board.boardCode()}")
            
            onProgress(ProgressUpdate("Extracting media files...", 0.3f))
            
            // Extract and save media files first
            Logger.d(TAG, "Extracting media files...")
            extractMediaFiles(importData, threadDescriptor, onProgress)
            
            Logger.d(TAG, "Media files extracted successfully")
            
            onProgress(ProgressUpdate("Creating thread structure...", 0.6f))
            
            // Create the thread in the database
            Logger.d(TAG, "Creating ChanThread...")
            val chanThread = createChanThread(threadDescriptor, importData, onProgress)
            
            Logger.d(TAG, "ChanThread created with ${chanThread.postsCount} posts")
            
            onProgress(ProgressUpdate("Saving thread to database...", 0.8f))
            
            // Save the thread to the database
            Logger.d(TAG, "Saving thread to database...")
            val savedThread = saveChanThread(chanThread)
            
            Logger.d(TAG, "Thread saved to database successfully")
            
            onProgress(ProgressUpdate("Finalizing import...", 0.9f))
            
            // Post-process thread data after import
            Logger.d(TAG, "Post-processing thread data...")
            threadDataPreloader.postloadThreadInfo(threadDescriptor)
            
            // Register thread with ThreadDownloadManager as completed
            Logger.d(TAG, "Registering imported thread with ThreadDownloadManager...")
            
            // Extract thumbnail URL from the first image in the OP post
            val threadThumbnailUrl = extractThreadThumbnailUrl(chanThread, threadDescriptor)
            
            // Extract OP post timestamp for proper sorting
            val opPostTimestamp = chanThread.getOriginalPost()?.timestamp?.let { DateTime(it) }
            Logger.d(TAG, "OP post timestamp: $opPostTimestamp")
            
            val registrationSuccess = threadDownloadManager.createCompletedThreadDownload(
                threadDescriptor = threadDescriptor,
                threadThumbnailUrl = threadThumbnailUrl,
                createdOn = opPostTimestamp
            )
            
            if (registrationSuccess) {
                Logger.d(TAG, "Thread successfully registered with ThreadDownloadManager")
            } else {
                Logger.w(TAG, "Failed to register thread with ThreadDownloadManager, but import was successful")
            }
            
            onProgress(ProgressUpdate("Import complete", 1.0f))
            
            Logger.d(TAG, "Thread import completed successfully: $threadDescriptor")
            
            threadDescriptor
        }
    }
    
    private suspend fun createChanThread(
        threadDescriptor: ChanDescriptor.ThreadDescriptor,
        importData: ThreadImportParser.ThreadImportData,
        onProgress: (ProgressUpdate) -> Unit
    ): ChanThread {
        val site = siteManager.bySiteDescriptor(threadDescriptor.siteDescriptor())
            ?: throw IllegalArgumentException("Site not found: ${threadDescriptor.siteDescriptor()}")
        
        val postParser = site.chanReader().getParser()
            ?: throw IllegalArgumentException("PostParser not found for site: ${site.name()}")
        
        val totalPosts = importData.posts.size
        val postBuilders = mutableListOf<ChanPostBuilder>()
        
        // Create post builders with raw data
        importData.posts.forEachIndexed { index, parsedPost ->
            val progress = 0.3f + (index.toFloat() / totalPosts) * 0.2f
            onProgress(ProgressUpdate("Creating post ${index + 1}/$totalPosts", progress))
            
            val postBuilder = ChanPostBuilder()
                .boardDescriptor(threadDescriptor.boardDescriptor())
                .id(parsedPost.postId)
                .opId(threadDescriptor.threadNo)
                .op(parsedPost.postId == threadDescriptor.threadNo)
                .name(parsedPost.name)
                .subject(parsedPost.subject)
                .comment(parsedPost.comment) // Raw HTML comment
                .setUnixTimestampSeconds(parsedPost.timestamp)
                .lastModified(System.currentTimeMillis())
            
            // Add images for this post
            val postImages = findImagesForPost(parsedPost, importData.mediaFiles, threadDescriptor)
            if (postImages.isNotEmpty()) {
                postBuilder.postImages(postImages, postBuilder.getPostDescriptor())
            }
            
            postBuilders.add(postBuilder)
        }
        
        // Use proper post parsing like thread downloader
        onProgress(ProgressUpdate("Parsing posts with site parser...", 0.5f))
        
        val parsingResult = parsePostsV1UseCase.parseNewPostsPosts(
            chanDescriptor = threadDescriptor,
            postParser = postParser,
            postBuildersToParse = postBuilders
        )
        
        val parsedPosts = parsingResult.parsedPosts
        Logger.d(TAG, "Parsed ${parsedPosts.size} posts using site parser")
        
        // Create thread with properly parsed posts
        val chanThread = ChanThread(
            isDevBuild = true,
            threadDescriptor = threadDescriptor,
            initialLastAccessTime = System.currentTimeMillis()
        )
        
        val opPost = parsedPosts.find { it.isOP() } as? ChanOriginalPost
            ?: throw IllegalArgumentException("No OP post found in parsed posts")
        
        chanThread.setOrUpdateOriginalPost(opPost)
        chanThread.addOrUpdatePosts(parsedPosts, null)
        
        return chanThread
    }
    
    private fun findImagesForPost(
        parsedPost: ThreadImportParser.ParsedPost,
        mediaFiles: List<String>,
        threadDescriptor: ChanDescriptor.ThreadDescriptor
    ): List<ChanPostImage> {
        val images = mutableListOf<ChanPostImage>()
        
        // Build the path to the extracted media directory
        val threadMediaDir = File(
            appConstants.threadDownloaderCacheDir,
            threadDescriptor.siteName() + "_" + threadDescriptor.boardCode() + "_" + threadDescriptor.threadNo
        )
        
        // Use the media files directly from the parsed post
        Logger.d(TAG, "Processing media files for post ${parsedPost.postId}")
        Logger.d(TAG, "Post media files: ${parsedPost.mediaFiles}")
        Logger.d(TAG, "Available media files: $mediaFiles")
        
        for (mediaFileName in parsedPost.mediaFiles) {
            // Skip if this media file wasn't found in the ZIP
            if (!mediaFiles.contains(mediaFileName)) {
                Logger.w(TAG, "Media file $mediaFileName not found in ZIP entries")
                continue
            }
            
            Logger.d(TAG, "Processing media file: $mediaFileName")
            
            val actualMediaFile = File(threadMediaDir, mediaFileName)
            
            // Find the thumbnail file (usually same name but with 's' suffix and .jpg extension)
            val baseName = File(mediaFileName).nameWithoutExtension
            val thumbnailFileName = "${baseName}s.jpg"
            val actualThumbnailFile = File(threadMediaDir, thumbnailFileName)
            
            // Create original site URLs that ThreadDownloadManager.findDownloadedFile() can resolve to local files
            // No network requests will be made - the system checks local cache first
            val originalUrls = createOriginalSiteUrls(threadDescriptor, mediaFileName, thumbnailFileName)
            Logger.d(TAG, "Creating original site URLs - mediaFileName: $mediaFileName, thumbnailFileName: $thumbnailFileName")
            val imageUrl = originalUrls.imageUrl
            val thumbnailUrl = originalUrls.thumbnailUrl
            Logger.d(TAG, "Created URLs - imageUrl: $imageUrl, thumbnailUrl: $thumbnailUrl")
            
            val imageBuilder = ChanPostImageBuilder()
                .serverFilename(mediaFileName)
                .filename(mediaFileName)
                .extension(File(mediaFileName).extension)
                .imageUrl(imageUrl)
                .thumbnailUrl(thumbnailUrl)
                .spoiler(false)
                .imageSize(if (actualMediaFile.exists()) actualMediaFile.length() else 0L)
            
            images.add(imageBuilder.build())
            
            Logger.d(TAG, "Created ChanPostImage for post ${parsedPost.postId}: $mediaFileName")
        }
        
        return images
    }
    
    private suspend fun saveChanThread(chanThread: ChanThread): ChanThread {
        // Get the thread database ID
        val threadDescriptor = chanThread.threadDescriptor
        val threadDatabaseId = chanPostRepository.createEmptyThreadIfNotExists(threadDescriptor)
            .unwrap() ?: throw IllegalStateException("Failed to create thread in database")
        
        // Save posts to database using the correct method name
        val allPosts = chanThread.getAll()
        chanPostRepository.insertOrUpdatePostsInDatabase(threadDatabaseId, allPosts)
            .unwrap()
        
        // Add the thread to the cache so media viewer can find it
        Logger.d(TAG, "Adding imported thread to cache for media viewer access")
        chanThreadsCache.putManyThreadPostsIntoCache(
            threadDescriptor = threadDescriptor,
            parsedPosts = allPosts,
            cacheOptions = ChanCacheOptions.onlyCacheInMemory(),
            chanCacheUpdateOptions = ChanCacheUpdateOptions.UpdateCache,
            postsFromServerData = null
        )
        
        return chanThread
    }
    
    private fun extractThreadThumbnailUrl(
        chanThread: ChanThread,
        threadDescriptor: ChanDescriptor.ThreadDescriptor
    ): String? {
        // Get the first image from the OP post for thumbnail
        val opPost = chanThread.getOriginalPost()
        val firstImage = opPost?.postImages?.firstOrNull()
        
        if (firstImage != null) {
            // Construct HTTP URL that ThreadDownloadManager can resolve to cache file
            val imageUrl = firstImage.actualThumbnailUrl?.toString()
            Logger.d(TAG, "Extracted thread thumbnail URL: $imageUrl")
            return imageUrl
        }
        
        Logger.d(TAG, "No thumbnail image found for imported thread")
        return null
    }
    
    private fun extractMediaFiles(
        importData: ThreadImportParser.ThreadImportData,
        threadDescriptor: ChanDescriptor.ThreadDescriptor,
        onProgress: (ProgressUpdate) -> Unit
    ) {
        val threadMediaDir = File(
            appConstants.threadDownloaderCacheDir,
            threadDescriptor.siteName() + "_" + threadDescriptor.boardCode() + "_" + threadDescriptor.threadNo
        )
        
        if (!threadMediaDir.exists()) {
            threadMediaDir.mkdirs()
        }
        
        val mediaFiles = importData.mediaFiles
        val totalFiles = mediaFiles.size
        
        // Use efficient ZIP streaming like backup import
        val inputStream = fileManager.getInputStream(importData.zipFile) ?: return
        
        inputStream.use { zis ->
            val zipInputStream = java.util.zip.ZipInputStream(zis)
            var entry = zipInputStream.nextEntry
            var extractedCount = 0
            
            while (entry != null) {
                if (!entry.isDirectory && mediaFiles.contains(entry.name)) {
                    val progress = 0.8f + (extractedCount.toFloat() / totalFiles) * 0.1f
                    onProgress(ProgressUpdate("Extracting file ${extractedCount + 1}/$totalFiles", progress))
                    
                    val outputFile = File(threadMediaDir, File(entry.name).name)
                    
                    try {
                        outputFile.outputStream().use { outputStream ->
                            zipInputStream.copyTo(outputStream, BUFFER_SIZE)
                        }
                        
                        extractedCount++
                        Logger.d(TAG, "Extracted media file: ${outputFile.absolutePath}")
                    } catch (error: Exception) {
                        Logger.e(TAG, "Failed to extract media file: ${entry.name}", error)
                    }
                }
                
                entry = zipInputStream.nextEntry
            }
        }
    }
    
    private fun extractFileFromZip(zipFile: ExternalFile, targetFileName: String, outputFile: File): Boolean {
        val inputStream = fileManager.getInputStream(zipFile) ?: return false
        
        inputStream.use { zis ->
            val zipInputStream = java.util.zip.ZipInputStream(zis)
            var entry = zipInputStream.nextEntry
            
            while (entry != null) {
                if (!entry.isDirectory && entry.name == targetFileName) {
                    Logger.d(TAG, "Found target file in ZIP: $targetFileName")
                    
                    outputFile.outputStream().use { outputStream ->
                        zipInputStream.copyTo(outputStream, BUFFER_SIZE)
                    }
                    
                    return true
                }
                entry = zipInputStream.nextEntry
            }
        }
        
        return false
    }
    
    private fun String.isMediaFile(): Boolean {
        val mediaExtensions = setOf("jpg", "jpeg", "png", "gif", "webm", "mp4", "webp")
        return mediaExtensions.any { this.endsWith(".$it", ignoreCase = true) }
    }
    
    /**
     * Creates original site URLs that ThreadDownloadManager can resolve to local cache files.
     * No network requests will be made - the system always checks local cache first.
     */
    private fun createOriginalSiteUrls(
        threadDescriptor: ChanDescriptor.ThreadDescriptor,
        mediaFileName: String,
        thumbnailFileName: String
    ): OriginalUrls {
        val serverFilename = File(mediaFileName).nameWithoutExtension
        val extension = File(mediaFileName).extension
        
        return when (threadDescriptor.siteName()) {
            "4chan" -> {
                // 4chan URL format: https://i.4cdn.org/BOARD/TIMESTAMP.EXT
                val boardCode = threadDescriptor.boardCode()
                val imageUrl = "https://i.4cdn.org/$boardCode/$mediaFileName".toHttpUrl()
                val thumbnailUrl = "https://i.4cdn.org/$boardCode/$thumbnailFileName".toHttpUrl()
                OriginalUrls(imageUrl, thumbnailUrl)
            }
            "dvach", "2ch" -> {
                // 2ch.hk URL format: https://2ch.hk/BOARD/src/TIMESTAMP.EXT
                val boardCode = threadDescriptor.boardCode()
                val imageUrl = "https://2ch.hk/$boardCode/src/$mediaFileName".toHttpUrl()
                val thumbnailUrl = "https://2ch.hk/$boardCode/thumb/$thumbnailFileName".toHttpUrl()
                OriginalUrls(imageUrl, thumbnailUrl)
            }
            "8kun" -> {
                // 8kun uses content-addressed storage, so we fallback to a generic pattern
                val imageUrl = "https://media.8kun.top/file_store/$mediaFileName".toHttpUrl()
                val thumbnailUrl = "https://media.8kun.top/file_store/$thumbnailFileName".toHttpUrl()
                OriginalUrls(imageUrl, thumbnailUrl)
            }
            else -> {
                // For unknown sites, use a generic pattern that ThreadDownloadManager can still resolve
                val imageUrl = "https://files.${threadDescriptor.siteName()}.local/$mediaFileName".toHttpUrl()
                val thumbnailUrl = "https://files.${threadDescriptor.siteName()}.local/$thumbnailFileName".toHttpUrl()
                OriginalUrls(imageUrl, thumbnailUrl)
            }
        }
    }
    
    data class OriginalUrls(
        val imageUrl: HttpUrl,
        val thumbnailUrl: HttpUrl
    )
    
    private fun tryExtractFileNameFromUri(uri: android.net.Uri, context: android.content.Context): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex > -1 && cursor.moveToFirst()) {
                    return@use cursor.getString(nameIndex)
                }
                return@use null
            }
        } catch (error: Throwable) {
            Logger.e(TAG, "Failed to extract filename from URI: $uri", error)
            null
        }
    }
    
    data class Params(
        val zipFile: ExternalFile,
        val appContext: android.content.Context,
        val onProgress: (ProgressUpdate) -> Unit
    )
    
    data class ProgressUpdate(
        val message: String,
        val percentage: Float
    )
    
    companion object {
        private const val TAG = "ImportThreadFromZipUseCase"
        private const val BUFFER_SIZE = 8192
    }
}
