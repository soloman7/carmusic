package com.carmusic.crash

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashHandler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val MAX_FILES = 20

    // SMTP 服务器配置，默认 163，由 AppContainer 在启动时从 SettingsRepository 注入用户设置
    @Volatile private var smtpHost: String = "smtp.163.com"
    @Volatile private var smtpPort: Int = 465

    fun configureSmtp(host: String, port: Int) {
        smtpHost = host
        smtpPort = port
    }

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
                // 只保留最近 MAX_FILES 个
                dir.listFiles()?.sortedBy { it.lastModified() }
                    ?.dropLast(MAX_FILES)
                    ?.forEach { it.delete() }
            }
            prev?.uncaughtException(t, e) ?: android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    fun crashDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "crash").apply { mkdirs() }

    fun listCrashes(context: Context): List<File> =
        crashDir(context).listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun crashCount(context: Context): Int = crashDir(context).listFiles()?.size ?: 0

    /**
     * 通过 SMTP（默认 163，可在设置中改 host/port）把崩溃日志发到指定邮箱。
     * 内部已切到 IO 协程执行，调用方可在任意线程调用；结果经 [onResult] 回调。
     */
    fun exportViaEmail(
        context: Context,
        smtpUser: String,
        smtpPass: String,
        toEmail: String,
        onResult: (Boolean, String) -> Unit
    ) {
        scope.launch {
            val files = listCrashes(context)
            if (files.isEmpty()) {
                onResult(false, "暂无崩溃日志")
                return@launch
            }
            val body = files.joinToString("\n\n==========\n\n") { f ->
                "== ${f.name} ==\n${f.readText()}"
            }
            try {
                sendEmail(smtpUser, smtpPass, toEmail, "CarMusic 崩溃日志 (${files.size})", body)
                onResult(true, "已发送 ${files.size} 条日志到 $toEmail")
            } catch (e: Exception) {
                onResult(false, "发送失败：${e.message}")
            }
        }
    }

    private fun sendEmail(user: String, pass: String, to: String, subject: String, body: String) {
        val port = smtpPort.toString()
        val props = java.util.Properties().apply {
            put("mail.smtp.host", smtpHost)
            put("mail.smtp.port", port)
            put("mail.smtp.auth", "true")
            put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
            put("mail.smtp.socketFactory.port", port)
            put("mail.smtp.socketFactory.fallback", "false")
        }
        val session = javax.mail.Session.getInstance(props, object : javax.mail.Authenticator() {
            override fun getPasswordAuthentication() = javax.mail.PasswordAuthentication(user, pass)
        })
        val message = javax.mail.internet.MimeMessage(session).apply {
            setFrom(javax.mail.internet.InternetAddress(user))
            setRecipients(javax.mail.Message.RecipientType.TO, to)
            // 指定 UTF-8 charset，否则中文主题按默认 ASCII 编码会乱码
            setSubject(subject, "UTF-8")
            setText(body, "UTF-8")
            sentDate = Date()
        }
        javax.mail.Transport.send(message)
    }
}
