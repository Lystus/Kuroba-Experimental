package com.github.k1rakishou.model.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration_v41_to_v42 : Migration(41, 42) {

  override fun migrate(database: SupportSQLiteDatabase) {
    database.doWithoutForeignKeys {
      database.execSQL("""
        CREATE TABLE IF NOT EXISTS `chan_post_image_metadata` (
          `image_hash` TEXT NOT NULL,
          `duration_ms` INTEGER,
          `has_audio` INTEGER,
          `extracted_at` INTEGER NOT NULL,
          PRIMARY KEY(`image_hash`)
        )
      """.trimIndent())
    }
  }

}
