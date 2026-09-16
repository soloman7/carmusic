package com.carmusic.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.carmusic.data.radio.RadioFavoriteDao
import com.carmusic.data.radio.RadioFavoriteEntity
import com.carmusic.data.radio.RadioStationDao
import com.carmusic.data.radio.RadioStationEntity

@Database(
    entities = [FavoriteEntity::class, HistoryEntity::class, LyricEntity::class, PlaybackStateEntity::class,
        RadioStationEntity::class, RadioFavoriteEntity::class],
    version = 6,
    exportSchema = true  // schema 输出到 app/schemas（构建侧 schemaLocation 已配置），供迁移比对
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun historyDao(): HistoryDao
    abstract fun lyricDao(): LyricDao
    abstract fun playbackStateDao(): PlaybackStateDao
    abstract fun radioStationDao(): RadioStationDao
    abstract fun radioFavoriteDao(): RadioFavoriteDao

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

        /**
         * v4 -> v5：新增电台两表（local-first，seed 随 APK 内置后导入）。
         * health/deleted/localDeadUntil 三列语义互斥（可见性三态，同步对后两列只读）。
         */
        // internal:MigrationTest 直接引用(迁移缺陷钉死在发版前)
        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `radio_stations` (" +
                        "`stationUuid` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`url` TEXT NOT NULL, " +
                        "`urlResolved` TEXT NOT NULL, " +
                        "`homepage` TEXT NOT NULL, " +
                        "`favicon` TEXT NOT NULL, " +
                        "`tags` TEXT NOT NULL, " +
                        "`country` TEXT NOT NULL, " +
                        "`countryCode` TEXT NOT NULL, " +
                        "`state` TEXT NOT NULL, " +
                        "`language` TEXT NOT NULL, " +
                        "`codec` TEXT NOT NULL, " +
                        "`bitrate` INTEGER NOT NULL, " +
                        "`hotRank` INTEGER NOT NULL, " +
                        "`health` INTEGER NOT NULL, " +
                        "`deleted` INTEGER NOT NULL, " +
                        "`localDeadUntil` INTEGER NOT NULL, " +
                        "`localDeadCount` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`stationUuid`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_radio_stations_state ON radio_stations(state)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_radio_stations_countryCode ON radio_stations(countryCode)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `radio_favorites` (" +
                        "`stationUuid` TEXT NOT NULL, " +
                        "`sortOrder` INTEGER NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`url` TEXT NOT NULL, " +
                        "`urlResolved` TEXT NOT NULL, " +
                        "`favicon` TEXT NOT NULL, " +
                        "`codec` TEXT NOT NULL, " +
                        "`bitrate` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`stationUuid`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_radio_favorites_sortOrder ON radio_favorites(sortOrder)")
            }
        }

        /**
         * v5 -> v6：radio_stations 加 votes/clickcount 两列（v6-D-B 排序口径：
         * clickcount=近期实际收听为主，votes=累计票为辅）。默认 0，旧行由 seed 版本门
         * 触发的全量重导补齐（seed 与 doSync 均显式写这两列）。
         */
        // internal:MigrationTest 直接引用(迁移缺陷钉死在发版前)
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE radio_stations ADD COLUMN votes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE radio_stations ADD COLUMN clickcount INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun build(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "carmusic.db"
            )
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                // v1 schema 无记录可查（exportSchema 此前为 false，未曾归档），
                // 无法为它补写显式 migration，只允许 v1 破坏性升级；
                // v2 起一律显式 migration，禁止全域 fallbackToDestructiveMigration。
                .fallbackToDestructiveMigrationFrom(1)
                .build()
        }
    }
}
