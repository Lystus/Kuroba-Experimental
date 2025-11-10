package com.github.k1rakishou.model.repository

import com.github.k1rakishou.common.ModularResult
import com.github.k1rakishou.model.KurobaDatabase
import com.github.k1rakishou.model.entity.chan.post.ChanPostImageMetadataEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class ChanPostImageMetadataRepository(
  private val database: KurobaDatabase,
  private val applicationScope: CoroutineScope
) : AbstractRepository(database) {
  private val TAG = "ChanPostImageMetadataRepository"
  
  private val _metadataUpdates = MutableSharedFlow<String>(extraBufferCapacity = 100)
  val metadataUpdates: SharedFlow<String> = _metadataUpdates.asSharedFlow()

  suspend fun store(imageHash: String, durationMs: Int?, hasAudio: Boolean?): ModularResult<Unit> {
    return applicationScope.dbCall {
      tryWithTransaction {
        val entity = ChanPostImageMetadataEntity(
          imageHash = imageHash,
          durationMs = durationMs,
          hasAudio = hasAudio,
          extractedAt = System.currentTimeMillis()
        )
        
        database.chanPostImageMetadataDao().insert(entity)
        _metadataUpdates.tryEmit(imageHash)
        Unit
      }
    }
  }

  suspend fun get(imageHash: String): ModularResult<ChanPostImageMetadataEntity?> {
    return applicationScope.dbCall {
      tryWithTransaction {
        database.chanPostImageMetadataDao().selectByImageHash(imageHash)
      }
    }
  }

  suspend fun delete(imageHash: String): ModularResult<Unit> {
    return applicationScope.dbCall {
      tryWithTransaction {
        database.chanPostImageMetadataDao().deleteByImageHash(imageHash)
        Unit
      }
    }
  }

  suspend fun count(): ModularResult<Int> {
    return applicationScope.dbCall {
      tryWithTransaction {
        database.chanPostImageMetadataDao().count()
      }
    }
  }

}
