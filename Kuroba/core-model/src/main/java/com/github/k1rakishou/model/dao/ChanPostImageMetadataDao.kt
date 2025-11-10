package com.github.k1rakishou.model.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.github.k1rakishou.model.entity.chan.post.ChanPostImageMetadataEntity

@Dao
abstract class ChanPostImageMetadataDao {

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract suspend fun insert(entity: ChanPostImageMetadataEntity): Long

  @Query("""
    SELECT *
    FROM ${ChanPostImageMetadataEntity.TABLE_NAME}
    WHERE ${ChanPostImageMetadataEntity.IMAGE_HASH_COLUMN_NAME} = :imageHash
  """)
  abstract suspend fun selectByImageHash(imageHash: String): ChanPostImageMetadataEntity?

  @Query("""
    DELETE FROM ${ChanPostImageMetadataEntity.TABLE_NAME}
    WHERE ${ChanPostImageMetadataEntity.IMAGE_HASH_COLUMN_NAME} = :imageHash
  """)
  abstract suspend fun deleteByImageHash(imageHash: String)

  @Query("""
    DELETE FROM ${ChanPostImageMetadataEntity.TABLE_NAME}
  """)
  abstract suspend fun deleteAll()

  @Query("""
    SELECT COUNT(*)
    FROM ${ChanPostImageMetadataEntity.TABLE_NAME}
  """)
  abstract suspend fun count(): Int
}
