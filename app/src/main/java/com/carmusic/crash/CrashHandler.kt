package com.carmusic.crash

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 未捕获异常落盘 + 保留策略。导出走系统分享（Settings 页），不再内置 SMTP。
 */
object CrashHandler {

    private const val MAX_FILES = 20

    fun install(context: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val dir = crashDir(context)
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val sw = StringWriter().also { e.printStackTrace(PrintWriter(it)) }
                File(dir, "crash_$ts.txt").writeText(buildString {
                    appendLine("time=$ts")
                    appendLine("device=${Build.MANUFACTURER}/${Build.MODEL} sdk=${Build.VERSION.SDK_INT}")
                    appendLine("thread=${t.name}")
                    appendLine()
                    appendLine(sw.toString())
                })
                pruneCrashFiles(dir.listFiles()?.toList() ?: emptyList())
            }
            prev?.uncaughtException(t, e) ?: android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    /** 保留最新 [maxFiles] 个崩溃日志（含刚写入的），删除其余。 */
    internal fun pruneCrashFiles(files: List<File>, maxFiles: Int = MAX_FILES) {
        files.sortedByDescending { it.lastModified() }
            .drop(maxFiles)
            .forEach { it.delete() }
    }

    fun crashDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "crash").apply { mkdirs() }

    fun listCrashes(context: Context): List<File> =
        crashDir(context).listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun crashCount(context: Context): Int = crashDir(context).listFiles()?.size ?: 0
}
