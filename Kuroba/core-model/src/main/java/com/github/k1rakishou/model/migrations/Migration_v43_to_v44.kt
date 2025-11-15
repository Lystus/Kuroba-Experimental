package com.github.k1rakishou.model.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration_v43_to_v44 : Migration(43, 44) {

  override fun migrate(database: SupportSQLiteDatabase) {
    database.doWithoutForeignKeys {
      // Add lastKnownSuccessCount column to track actual progress in downloads
      // This prevents false termination when cycles increment but media is still downloading
      database.execSQL("""
        ALTER TABLE `thread_download_entity` 
        ADD COLUMN `last_known_success_count` INTEGER NOT NULL DEFAULT 0
      """.trimIndent())
    }
  }

}
