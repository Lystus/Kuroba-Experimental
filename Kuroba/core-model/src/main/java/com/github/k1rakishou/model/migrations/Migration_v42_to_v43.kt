package com.github.k1rakishou.model.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration_v42_to_v43 : Migration(42, 43) {

  override fun migrate(database: SupportSQLiteDatabase) {
    database.doWithoutForeignKeys {
      // Create media_download_attempts table for tracking retry logic
      database.execSQL("""
        CREATE TABLE IF NOT EXISTS `media_download_attempts` (
          `image_url` TEXT NOT NULL,
          `thread_descriptor` TEXT NOT NULL,
          `attempt_count` INTEGER NOT NULL DEFAULT 0,
          `last_attempt_time` INTEGER NOT NULL DEFAULT 0,
          `last_error_type` TEXT,
          `is_permanent_failure` INTEGER NOT NULL DEFAULT 0,
          `retry_after_timestamp` INTEGER,
          PRIMARY KEY(`image_url`)
        )
      """.trimIndent())

      // Create indices for cleanup and filtering
      database.execSQL("""
        CREATE INDEX IF NOT EXISTS `index_media_download_attempts_thread_descriptor` 
        ON `media_download_attempts` (`thread_descriptor`)
      """.trimIndent())

      database.execSQL("""
        CREATE INDEX IF NOT EXISTS `index_media_download_attempts_last_attempt_time` 
        ON `media_download_attempts` (`last_attempt_time`)
      """.trimIndent())

      // Add new columns to thread_download_entity for cycle tracking
      database.execSQL("""
        ALTER TABLE `thread_download_entity` 
        ADD COLUMN `download_cycles_count` INTEGER NOT NULL DEFAULT 0
      """.trimIndent())

      database.execSQL("""
        ALTER TABLE `thread_download_entity` 
        ADD COLUMN `last_progress_time` INTEGER
      """.trimIndent())

      database.execSQL("""
        ALTER TABLE `thread_download_entity` 
        ADD COLUMN `cycles_since_progress` INTEGER NOT NULL DEFAULT 0
      """.trimIndent())
    }
  }

}
