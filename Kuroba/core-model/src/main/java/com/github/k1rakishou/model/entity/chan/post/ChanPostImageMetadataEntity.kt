package com.github.k1rakishou.model.entity.chan.post

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stores extracted metadata for media files (video/audio/gif) in downloaded threads.
 * Used to display duration and audio presence indicators on thumbnails.
 */
@Entity(tableName = ChanPostImageMetadataEntity.TABLE_NAME)
data class ChanPostImageMetadataEntity(
  @PrimaryKey
  @ColumnInfo(name = IMAGE_HASH_COLUMN_NAME)
  val imageHash: String,
  @ColumnInfo(name = DURATION_MS_COLUMN_NAME)
  val durationMs: Int?,
  @ColumnInfo(name = HAS_AUDIO_COLUMN_NAME)
  val hasAudio: Boolean?,
  @ColumnInfo(name = EXTRACTED_AT_COLUMN_NAME)
  val extractedAt: Long
) {

  companion object {
    const val TABLE_NAME = "chan_post_image_metadata"
    
    const val IMAGE_HASH_COLUMN_NAME = "image_hash"
    const val DURATION_MS_COLUMN_NAME = "duration_ms"
    const val HAS_AUDIO_COLUMN_NAME = "has_audio"
    const val EXTRACTED_AT_COLUMN_NAME = "extracted_at"
  }
}
