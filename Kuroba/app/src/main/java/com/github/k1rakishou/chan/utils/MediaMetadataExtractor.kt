package com.github.k1rakishou.chan.utils

import android.media.MediaMetadataRetriever
import com.github.k1rakishou.common.errorMessageOrClassName
import com.github.k1rakishou.core_logger.Logger
import java.io.File

/**
 * Extracts metadata from media files (videos, audio) for display purposes
 */
class MediaMetadataExtractor {
  private val TAG = "MediaMetadataExtractor"

  data class MediaMetadata(
    val durationMs: Int?,
    val hasAudio: Boolean?
  )

  /**
   * Extract duration and audio information from a media file
   * @param file The media file to extract metadata from
   * @return MediaMetadata with duration and audio info, or null if extraction failed
   */
  suspend fun extract(file: File): MediaMetadata? {
    if (!file.exists()) {
      Logger.w(TAG, "extract() File does not exist: ${file.absolutePath}")
      return null
    }

    var retriever: MediaMetadataRetriever? = null
    
    try {
      retriever = MediaMetadataRetriever()
      retriever.setDataSource(file.absolutePath)

      val durationString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
      val hasAudioString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)

      val durationMs = durationString?.toIntOrNull()
      val hasAudio = hasAudioString == "yes"

      Logger.d(TAG, "extract() Successfully extracted metadata for ${file.name}: duration=${durationMs}ms, hasAudio=$hasAudio")
      
      return MediaMetadata(
        durationMs = durationMs,
        hasAudio = hasAudio
      )
    } catch (error: Throwable) {
      Logger.e(TAG, "extract() Failed to extract metadata from ${file.name}: ${error.errorMessageOrClassName()}")
      return null
    } finally {
      try {
        retriever?.release()
      } catch (error: Throwable) {
        Logger.e(TAG, "extract() Failed to release MediaMetadataRetriever: ${error.errorMessageOrClassName()}")
      }
    }
  }

}
