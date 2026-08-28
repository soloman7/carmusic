package com.carmusic.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [FavoriteEntity::class, HistoryEntity::class, LyricEntity::class, PlaybackStateEntity::class],
    version = 4,
    exportSchema = true  // schema 输出到 app/schemas（构建侧 schemaLocation 已配置），供迁移比对
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun historyDao(): HistoryDao
    abstract fun lyricDao(): LyricDao
    abstract fun playbackStateDao(): PlaybackStateDao

    companion object {
        /**
         * v2 -> v3：给 history.playedAt 加索引（与 HistoryEntity 的 @Index 对应）。
         * 仅加索引，无数据搬迁。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_history_playedAt ON history(playedAt)")
            }
        }

        /**
         * v3 -> v4：新增 playback_state 单行表（播放会话快照，杀进程恢复用）。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `playback_state` (" +
                        "`id` INTEGER NOT NULL, " +
                        "`queueJson` TEXT NOT NULL, " +
                        "`currentIndex` INTEGER NOT NULL, " +
                        "`positionMs` INTEGER NOT NULL, " +
                        "`savedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        fun build(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "carmusic.db"
            )
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                // v1 schema 无记录可查（exportSchema 此前为 false，未曾归档），
                // 无法为它补写显式 migration，只允许 v1 破坏性升级；
                // v2 起一律显式 migration，禁止全域 fallbackToDestructiveMigration。
                .fallbackToDestructiveMigrationFrom(1)
                .build()
        }
    }
}
