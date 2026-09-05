package com.carmusic.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 崩溃日志保留策略测试。
 * 回归背景：v3.3 及之前 sortedBy(升序)+dropLast 把"最新的 20 条"删掉、只留最旧的——
 * 刚写完的崩溃日志当轮即被删，exportViaEmail 导出的永远是陈年日志。
 */
class CrashHandlerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun makeFile(dir: File, name: String, ageMs: Long): File {
        val f = File(dir, name).apply { writeText("crash $name") }
        assertTrue(f.setLastModified(System.currentTimeMillis() - ageMs))
        return f
    }

    @Test
    fun `prune keeps the newest files and deletes the oldest`() {
        val dir = tmp.newFolder()
        val files = (1..21).map { makeFile(dir, "crash_$it.txt", (100 - it) * 1000L) }
        // crash_21 最新（age 最小），crash_1 最旧

        CrashHandler.pruneCrashFiles(files, maxFiles = 20)

        assertTrue("最旧的 crash_1 应被删除", !File(dir, "crash_1.txt").exists())
        assertTrue("最新的 crash_21 必须保留", File(dir, "crash_21.txt").exists())
        assertEquals(20, dir.listFiles()?.size)
    }

    @Test
    fun `prune keeps everything when under limit`() {
        val dir = tmp.newFolder()
        val files = (1..5).map { makeFile(dir, "crash_$it.txt", it * 1000L) }

        CrashHandler.pruneCrashFiles(files, maxFiles = 20)

        assertEquals(5, dir.listFiles()?.size)
    }

    @Test
    fun `freshly written crash file survives the prune it triggers`() {
        // 模拟第 21 次崩溃：先有 20 条旧日志，新写的这条必须活过本轮清理
        val dir = tmp.newFolder()
        val old = (1..20).map { makeFile(dir, "old_$it.txt", (100 - it) * 1000L) }
        val fresh = makeFile(dir, "crash_fresh.txt", 0L)   // 刚写入

        CrashHandler.pruneCrashFiles(old + fresh, maxFiles = 20)

        assertTrue("刚写入的崩溃日志不能被本轮清理删掉", fresh.exists())
        assertFalse(File(dir, "old_1.txt").exists())
    }
}
