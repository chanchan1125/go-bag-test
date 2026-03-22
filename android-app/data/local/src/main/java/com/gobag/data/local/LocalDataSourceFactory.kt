package com.gobag.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object LocalDataSourceFactory {
    private val migration_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS recommended_templates (
                    template_id TEXT NOT NULL,
                    category TEXT NOT NULL,
                    name TEXT NOT NULL,
                    recommended_qty REAL NOT NULL,
                    unit TEXT NOT NULL,
                    priority TEXT NOT NULL,
                    tips TEXT NOT NULL,
                    PRIMARY KEY(template_id, category, name)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS conflicts (
                    item_id TEXT NOT NULL PRIMARY KEY,
                    server_json TEXT NOT NULL,
                    reason TEXT NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    fun create_db(context: Context): GoBagDatabase {
        return Room.databaseBuilder(
            context,
            GoBagDatabase::class.java,
            "gobag.db"
        ).addMigrations(migration_1_2).build()
    }
}
