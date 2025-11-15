package com.github.k1rakishou.model.entity.download

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  tableName = MediaDownloadAttemptEntity.TABLE_NAME,
  indices = [
    Index(value = [MediaDownloadAttemptEntity.THREAD_DESCRIPTOR_COLUMN_NAME]),
    Index(value = [MediaDownloadAttemptEntity.LAST_ATTEMPT_TIME_COLUMN_NAME])
  ]
)
data class MediaDownloadAttemptEntity(
  @PrimaryKey(autoGenerate = false)
  @ColumnInfo(name = IMAGE_URL_COLUMN_NAME)
  val imageUrl: String,
  @ColumnInfo(name = THREAD_DESCRIPTOR_COLUMN_NAME)
  val threadDescriptor: String,
  @ColumnInfo(name = ATTEMPT_COUNT_COLUMN_NAME)
  val attemptCount: Int = 0,
  @ColumnInfo(name = LAST_ATTEMPT_TIME_COLUMN_NAME)
  val lastAttemptTime: Long = 0L,
  @ColumnInfo(name = LAST_ERROR_TYPE_COLUMN_NAME)
  val lastErrorType: String? = null,
  @ColumnInfo(name = IS_PERMANENT_FAILURE_COLUMN_NAME)
  val isPermanentFailure: Boolean = false,
  @ColumnInfo(name = RETRY_AFTER_TIMESTAMP_COLUMN_NAME)
  val retryAfterTimestamp: Long? = null
) {

  companion object {
    const val TABLE_NAME = "media_download_attempts"

    const val IMAGE_URL_COLUMN_NAME = "image_url"
    const val THREAD_DESCRIPTOR_COLUMN_NAME = "thread_descriptor"
    const val ATTEMPT_COUNT_COLUMN_NAME = "attempt_count"
    const val LAST_ATTEMPT_TIME_COLUMN_NAME = "last_attempt_time"
    const val LAST_ERROR_TYPE_COLUMN_NAME = "last_error_type"
    const val IS_PERMANENT_FAILURE_COLUMN_NAME = "is_permanent_failure"
    const val RETRY_AFTER_TIMESTAMP_COLUMN_NAME = "retry_after_timestamp"
  }
}
