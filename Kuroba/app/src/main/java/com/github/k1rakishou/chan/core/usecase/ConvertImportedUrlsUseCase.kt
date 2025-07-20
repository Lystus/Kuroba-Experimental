package com.github.k1rakishou.chan.core.usecase

import com.github.k1rakishou.chan.core.manager.BoardManager
import com.github.k1rakishou.chan.core.manager.SiteManager
import com.github.k1rakishou.common.ModularResult
import com.github.k1rakishou.core_logger.Logger
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import com.github.k1rakishou.model.data.descriptor.SiteDescriptor
import com.github.k1rakishou.model.data.post.ChanPostImage
import com.github.k1rakishou.model.repository.ChanPostImageRepository
import com.github.k1rakishou.model.repository.ChanPostRepository
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * UseCase for converting imported-thread.local URLs back to original site URLs
 * during backup export operations.
 */
class ConvertImportedUrlsUseCase(
    private val chanPostRepository: ChanPostRepository,
    private val chanPostImageRepository: ChanPostImageRepository,
    private val siteManager: SiteManager,
    private val boardManager: BoardManager
) : ISuspendUseCase<ConvertImportedUrlsUseCase.Params, ModularResult<Unit>> {

    override suspend fun execute(parameter: Params): ModularResult<Unit> {
        return ModularResult.Try {
            Logger.d(TAG, "Starting URL conversion for backup export")
            
            // Get all post images with imported-thread.local URLs
            val importedImages = getImportedThreadImages().unwrap()
            Logger.d(TAG, "Found ${importedImages.size} imported images to convert")
            
            // Convert each image URL back to original format
            val convertedImages = convertImportedUrls(importedImages)
            Logger.d(TAG, "Successfully converted ${convertedImages.size} image URLs")
            
            // Update the database with converted URLs
            updateImageUrls(convertedImages).unwrap()
            
            Logger.d(TAG, "URL conversion completed successfully")
        }
    }
    
    private suspend fun getImportedThreadImages(): ModularResult<List<ChanPostImage>> {
        return ModularResult.Try {
            // This would need to be implemented to fetch all post images from the database
            // that have imported-thread.local URLs. For now, return empty list as placeholder.
            // 
            // In a real implementation, you would:
            // 1. Query ChanPostImageRepository for images with imported-thread.local URLs
            // 2. Group them by thread descriptor
            // 3. Return the list for processing
            
            emptyList<ChanPostImage>()
        }
    }
    
    private suspend fun convertImportedUrls(images: List<ChanPostImage>): List<Pair<ChanPostImage, ConvertedUrls>> {
        val converted = mutableListOf<Pair<ChanPostImage, ConvertedUrls>>()
        
        for (image in images) {
            val threadDescriptor = image.ownerPostDescriptor.threadDescriptor()
            val originalUrls = reconstructOriginalUrls(threadDescriptor, image)
            
            if (originalUrls != null) {
                converted.add(image to originalUrls)
                Logger.d(TAG, "Converted ${image.serverFilename}: ${image.imageUrl} -> ${originalUrls.imageUrl}")
            } else {
                Logger.w(TAG, "Failed to convert URL for image: ${image.serverFilename}")
            }
        }
        
        return converted
    }
    
    private fun reconstructOriginalUrls(
        threadDescriptor: ChanDescriptor.ThreadDescriptor,
        image: ChanPostImage
    ): ConvertedUrls? {
        val site = siteManager.bySiteDescriptor(threadDescriptor.siteDescriptor())
            ?: return null
            
        val board = boardManager.byBoardDescriptor(threadDescriptor.boardDescriptor())
            ?: return null
            
        val endpoints = site.endpoints()
        
        return try {
            // Extract the filename from the server filename
            val serverFilename = image.serverFilename
            val extension = image.extension ?: return null
            
            // For 4chan, construct URLs using the site's endpoint system
            when (threadDescriptor.siteName()) {
                "4chan" -> {
                    // 4chan URL pattern: https://i.4cdn.org/BOARD/TIMESTAMP.EXT
                    // Thumbnail pattern: https://i.4cdn.org/BOARD/TIMESTAMPs.jpg
                    val baseImageUrl = "https://i.4cdn.org/${threadDescriptor.boardCode()}/$serverFilename.$extension"
                    val baseThumbnailUrl = "https://i.4cdn.org/${threadDescriptor.boardCode()}/${serverFilename}s.jpg"
                    
                    ConvertedUrls(
                        imageUrl = baseImageUrl.toHttpUrl(),
                        thumbnailUrl = baseThumbnailUrl.toHttpUrl()
                    )
                }
                "8kun" -> {
                    // 8kun URL pattern: https://media.8kun.top/file_store/HASH.EXT
                    // For now, fallback to keeping the imported URL since we can't reconstruct 8kun URLs
                    null
                }
                "dvach", "2ch" -> {
                    // 2ch.hk URL pattern: https://2ch.hk/BOARD/src/TIMESTAMP.EXT
                    val baseImageUrl = "https://2ch.hk/${threadDescriptor.boardCode()}/src/$serverFilename.$extension"
                    val baseThumbnailUrl = "https://2ch.hk/${threadDescriptor.boardCode()}/thumb/${serverFilename}s.jpg"
                    
                    ConvertedUrls(
                        imageUrl = baseImageUrl.toHttpUrl(),
                        thumbnailUrl = baseThumbnailUrl.toHttpUrl()
                    )
                }
                else -> {
                    // For unknown sites, try to use the site's endpoint system
                    try {
                        val args = mapOf(
                            "tim" to serverFilename,
                            "ext" to extension
                        )
                        
                        val imageUrl = endpoints.imageUrl(threadDescriptor.boardDescriptor(), args)
                        val thumbnailUrl = endpoints.thumbnailUrl(
                            threadDescriptor.boardDescriptor(),
                            false,
                            0,
                            args
                        )
                        
                        ConvertedUrls(
                            imageUrl = imageUrl,
                            thumbnailUrl = thumbnailUrl
                        )
                    } catch (e: Exception) {
                        Logger.w(TAG, "Failed to reconstruct URLs for site: ${threadDescriptor.siteName()}: ${e.message}")
                        null
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Error reconstructing URLs for ${image.serverFilename}", e)
            null
        }
    }
    
    private suspend fun updateImageUrls(convertedImages: List<Pair<ChanPostImage, ConvertedUrls>>): ModularResult<Unit> {
        return ModularResult.Try {
            // This would need to be implemented to update the database
            // In a real implementation, you would:
            // 1. Create new ChanPostImageEntity objects with converted URLs
            // 2. Update them in the database using ChanPostImageRepository
            // 3. Handle any database transaction requirements
            
            for ((image, convertedUrls) in convertedImages) {
                Logger.d(TAG, "Would update ${image.serverFilename} URLs in database")
                // TODO: Implement database update logic
            }
        }
    }
    
    data class ConvertedUrls(
        val imageUrl: HttpUrl,
        val thumbnailUrl: HttpUrl
    )
    
    data class Params(
        val dryRun: Boolean = false // For testing purposes
    )
    
    companion object {
        private const val TAG = "ConvertImportedUrlsUseCase"
    }
}
