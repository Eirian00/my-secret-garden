package com.kasuga.supervisor

import android.app.AppOpsManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : androidx.appcompat.app.AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("config", MODE_PRIVATE) }
    private lateinit var serverInput: EditText
    private lateinit var statusText: TextView
    private lateinit var usageText: TextView

    // 默认监督目标：用户可在代码中扩展包名。
    private val watched = linkedMapOf(
        "抖音" to "com.ss.android.ugc.aweme",
        "哔哩哔哩" to "tv.danmaku.bili",
        "小红书" to "com.xingin.xhs"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        val title = TextView(this).apply {
            text = "春日监督"
            textSize = 28f
        }
        layout.addView(title)

        val tip = TextView(this).apply {
            text = "读取手机 App 使用时间，并发送到你的监督服务器。"
            textSize = 16f
        }
        layout.addView(tip)

        serverInput = EditText(this).apply {
            hint = "服务器地址，例如 http://82.xxx.xxx.xxx:8090"
            setText(prefs.getString("server", ""))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        layout.addView(serverInput)

        val save = Button(this).apply {
            text = "保存服务器地址"
            setOnClickListener {
                prefs.edit().putString("server", serverInput.text.toString().trim().removeSuffix("/")).apply()
                statusText.text = "已保存"
            }
        }
        layout.addView(save)

        val permission = Button(this).apply {
            text = "开启“使用情况访问”权限"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }
        layout.addView(permission)

        statusText = TextView(this).apply {
            text = "状态：等待设置"
            textSize = 16f
        }
        layout.addView(statusText)

        val refresh = Button(this).apply {
            text = "读取并发送今日使用时间"
            setOnClickListener { collectAndSend() }
        }
        layout.addView(refresh)

        usageText = TextView(this).apply {
            text = "今日使用：尚未读取"
            textSize = 17f
        }
        layout.addView(usageText)

        setContentView(layout)
    }

    private fun hasUsagePermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun collectAndSend() {
        if (!hasUsagePermission()) {
            statusText.text = "状态：请先开启使用情况访问权限"
            return
        }

        val server = serverInput.text.toString().trim().removeSuffix("/")
        if (server.isBlank()) {
            statusText.text = "状态：请先填写服务器地址"
            return
        }

        thread {
            val now = System.currentTimeMillis()
            val start = java.time.LocalDate.now()
                .atStartOfDay(java.time.ZoneId.systemDefault())
                .toInstant().toEpochMilli()

            val manager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val stats = manager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, start, now
            ).associateBy { it.packageName }

            val result = linkedMapOf<String, Any>()
            for ((name, pkg) in watched) {
                val ms = stats[pkg]?.totalTimeInForeground ?: 0L
                result[name] = ms / 60000L
            }

            val json = buildJson(result)
            val response = post(server + "/report", json)

            runOnUiThread {
                usageText.text = result.entries.joinToString(
                    prefix = "今日使用：\n",
                    separator = "\n"
                ) { "${it.key}：${it.value} 分钟" }
                statusText.text = response
            }
        }
    }

    private fun buildJson(result: Map<String, Any>): String {
        val items = result.entries.joinToString(",") {
            "\"${it.key}\":${it.value}"
        }
        return """{"device":"android","usage":{$items}}"""
    }

    private fun post(url: String, body: String): String {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("X-Supervisor-Token", "KasugaPhone_2026_change_me")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code in 200..299) "状态：服务器已收到数据" else "状态：服务器返回 $code"
        } catch (e: Exception) {
            "状态：连接失败，请检查地址和服务器"
        }
    }
}
