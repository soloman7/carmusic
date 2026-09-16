package com.carmusic.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.carmusic.data.radio.RadioStationEntity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 迁移回归测试(v3.5.1 补,5→6 于 v3.7.0 扩展):v3.5.0 的 MIGRATION_4_5 在迁移 SQL 里建了
 * 两个索引,但实体未声明 @Index → Room schema 校验失败 → 车机升级后一切 DB 访问抛异常。
 *
 * 实现:按 app/schemas/{v}.json 的原始建表 SQL 手搓一个真 v{N} 库(user_version=N),
 * 用 Room 打开 → 跑迁移 → Room 按实体 schema 校验结果 → 并验证业务数据存活。
 * 不走 MigrationTestHelper 的资产管道(该管道在本项目 AGP 版本下不通),直接消费导出的 schema。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    private fun buildDatabaseFromSchema(context: Context, name: String, version: Int) {
        val schemaJson = File("schemas/com.carmusic.data.AppDatabase/$version.json").readText()
        val schema = Gson().fromJson<Map<String, Any>>(
            schemaJson, object : TypeToken<Map<String, Any>>() {}.type
        )
        val dbFile = context.getDatabasePath(name)
        dbFile.parentFile?.mkdirs()
        dbFile.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        val entities = (schema["database"] as Map<*, *>)["entities"] as List<Map<*, *>>
        for (entity in entities) {
            val tableName = entity["tableName"] as String
            val createSql = (entity["createSql"] as String).replace("\${TABLE_NAME}", tableName)
            db.execSQL(createSql)
            val indices = (entity["indices"] as? List<Map<*, *>>) ?: emptyList()
            for (index in indices) {
                db.execSQL((index["createSql"] as String).replace("\${TABLE_NAME}", tableName))
            }
        }
        db.execSQL(
            "INSERT INTO playback_state (id, queueJson, currentIndex, positionMs, savedAt) " +
                "VALUES (0, '[]', 0, 0, 1)"
        )
        db.version = version
        db.close()
        val check = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        check.rawQuery("PRAGMA user_version", null).use { c ->
            c.moveToFirst()
            assertEquals(
                "user_version 必须持久化为 $version,否则 Room 会走 onCreate 跳过迁移",
                version, c.getInt(0)
            )
        }
        check.close()
    }

    @Test
    fun `migrate 4 to 5 creates radio tables matching entity schema`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        buildDatabaseFromSchema(context, "migration-test-4-5", 4)

        // Room 以 user_version=4 打开 → 执行 MIGRATION_4_5(+5→6 链)→ 按实体 schema 校验
        // (索引与实体 @Index 不一致会在此抛 "Migration didn't properly handle")
        val room = Room.databaseBuilder(context, AppDatabase::class.java, "migration-test-4-5")
            .addMigrations(AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()

        try {
            runBlocking {
                // 迁移后 radio 两表真实可用
                room.radioStationDao().upsertAll(
                    listOf(
                        RadioStationEntity(
                            stationUuid = "u1", name = "测试台", url = "https://s/1",
                            urlResolved = "https://s/1", homepage = "", favicon = "", tags = "pop",
                            country = "China", countryCode = "CN", state = "广东", language = "chinese",
                            codec = "MP3", bitrate = 128, hotRank = 0, health = 1,
                            deleted = false, localDeadUntil = 0, localDeadCount = 0
                        )
                    )
                )
                assertEquals(1, room.radioStationDao().count())
                // v4 业务数据存活
                assertTrue(
                    room.openHelper.readableDatabase.query("SELECT COUNT(*) FROM playback_state")
                        .use { it.moveToFirst() && it.getInt(0) == 1 }
                )
            }
        } finally {
            room.openHelper.readableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='radio_stations'"
            ).use { c ->
                val names = mutableListOf<String>()
                while (c.moveToNext()) names.add(c.getString(0))
                assertTrue("迁移必须真的执行(索引已建):" + names, names.contains("index_radio_stations_state"))
            }
            room.close()
        }
    }

    /**
     * 5→6(v6-D-B):votes/clickcount 以 ADD COLUMN ... DEFAULT 0 迁入;
     * 旧行落默认 0,新行显式值可写回。默认值与实体 @ColumnInfo(defaultValue) 不一致
     * 会在 Room 校验处直接抛错(本测试即锁这一点)。
     */
    @Test
    fun `migrate 5 to 6 adds votes and clickcount with zero defaults`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        buildDatabaseFromSchema(context, "migration-test-5-6", 5)

        // v5 库预置一行电台(无 votes/clickcount 列)
        val pre = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("migration-test-5-6"), null)
        pre.execSQL(
            "INSERT INTO radio_stations (stationUuid, name, url, urlResolved, homepage, favicon, tags, " +
                "country, countryCode, state, language, codec, bitrate, hotRank, health, deleted, " +
                "localDeadUntil, localDeadCount) VALUES " +
                "('old1','老台','https://s/1','https://s/1','','','pop','China','CN','','chinese','MP3',128,0,1,0,0,0)"
        )
        pre.close()

        val room = Room.databaseBuilder(context, AppDatabase::class.java, "migration-test-5-6")
            .addMigrations(AppDatabase.MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()

        try {
            runBlocking {
                // 旧行存活,两新列落默认 0
                val old = room.radioStationDao().getByUuid("old1")!!
                assertEquals("老台", old.name)
                assertEquals("迁移旧行 votes 落默认 0", 0, old.votes)
                assertEquals("迁移旧行 clickcount 落默认 0", 0, old.clickcount)

                // 新行显式写入两列并读回
                room.radioStationDao().upsertAll(
                    listOf(
                        RadioStationEntity(
                            stationUuid = "new1", name = "新台", url = "https://s/2",
                            urlResolved = "https://s/2", homepage = "", favicon = "", tags = "rock",
                            country = "United States", countryCode = "US", state = "", language = "english",
                            codec = "MP3", bitrate = 128, hotRank = 0, votes = 42, clickcount = 7, health = 1,
                            deleted = false, localDeadUntil = 0, localDeadCount = 0
                        )
                    )
                )
                val fresh = room.radioStationDao().getByUuid("new1")!!
                assertEquals(42, fresh.votes)
                assertEquals(7, fresh.clickcount)
            }
        } finally {
            room.close()
        }
    }
}
