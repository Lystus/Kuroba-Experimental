package com.github.k1rakishou.model.data.download

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.github.k1rakishou.model.entity.download.MediaDownloadAttemptEntity

@Dao
abstract class MediaDownloadAttemptDao {

  @Query("SELECT * FROM ${MediaDownloadAttemptEntity.TABLE_NAME} WHERE ${MediaDownloadAttemptEntity.IMAGE_URL_COLUMN_NAME} = :url")
  abstract suspend fun getAttempt(url: String): MediaDownloadAttemptEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract suspend fun insertOrUpdate(attempt: MediaDownloadAttemptEntity)

  @Query("DELETE FROM ${MediaDownloadAttemptEntity.TABLE_NAME} WHERE ${MediaDownloadAttemptEntity.IMAGE_URL_COLUMN_NAME} = :url")
  abstract suspend fun deleteByUrl(url: String)

  @Query("DELETE FROM ${MediaDownloadAttemptEntity.TABLE_NAME} WHERE ${MediaDownloadAttemptEntity.THREAD_DESCRIPTOR_COLUMN_NAME} = :descriptor")
  abstract suspend fun deleteByThread(descriptor: String)

  @Query("DELETE FROM ${MediaDownloadAttemptEntity.TABLE_NAME} WHERE ${MediaDownloadAttemptEntity.LAST_ATTEMPT_TIME_COLUMN_NAME} < :cutoffTime")
  abstract suspend fun deleteOldAttempts(cutoffTime: Long)

  @Query("SELECT COUNT(*) FROM ${MediaDownloadAttemptEntity.TABLE_NAME} WHERE ${MediaDownloadAttemptEntity.THREAD_DESCRIPTOR_COLUMN_NAME} = :descriptor")
  abstract suspend fun countAttemptsByThread(descriptor: String): Int

  @Query("""
    SELECT COUNT(*) FROM ${MediaDownloadAttemptEntity.TABLE_NAME} 
    WHERE ${MediaDownloadAttemptEntity.THREAD_DESCRIPTOR_COLUMN_NAME} = :descriptor
    AND ${MediaDownloadAttemptEntity.IS_PERMANENT_FAILURE_COLUMN_NAME} = 1
  """)
  abstract suspend fun countPermanentFailuresByThread(descriptor: String): Int
}
