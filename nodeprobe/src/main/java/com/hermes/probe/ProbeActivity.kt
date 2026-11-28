package com.hermes.probe

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 把每一步直接显示在屏幕上（不依赖日志系统）。 */
class ProbeActivity : Activity() {

    private lateinit var out: TextView
    private val lines = StringBuilder()

    companion object {
        @Volatile var keepAlive: Any? = null
    }

    private fun say(line: String) {
        Log.i("PROBE", line)
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        runOnUiThread {
            lines.append(ts).append("  ").append(line).append('\n')
            out.text = lines.toString()
        }
        try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Operit")
            if (!dir.exists()) dir.mkdirs()
            File(dir, "probe-node.log").appendText("$ts  $line\n")
        } catch (_: Throwable) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        )
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setBackgroundColor(Color.parseColor("#0D0E10"))
        layout.setPadding(28, 70, 28, 28)
        out = TextView(this)
        out.setTextColor(Color.parseColor("#E8EAED"))
        out.textSize = 13f
        out.setTextIsSelectable(true)
        layout.addView(out, lp)
        setContentView(ScrollView(this).apply { addView(layout) })
        say("== probe 启动 ==")

        Thread({
            try {
                say("加载 c++_shared ...")
                System.loadLibrary("c++_shared")
                say("加载 emutls ...")
                runCatching { System.loadLibrary("hermes_emutls") }
                say("加载 node ...")
                System.loadLibrary("node")
                say("加载自己的入口 ...")
                System.loadLibrary("hermes_node_jni")
                say("四个库都加载成功")

                val workDir = File(filesDir, "node").apply { mkdirs() }
                val bootstrap = File(workDir, "bootstrap.js")
                bootstrap.writeText("console.log('### HERMES-NODE alive ' + process.version);\n")
                say("脚本：" + bootstrap.absolutePath)
                say("调用 node::Start（会一直跑，直到退出）...")
                val code = com.hermes.node.NodeLauncher.startNative(bootstrap.absolutePath)
                say("startNative 返回：" + code)
            } catch (e: Throwable) {
                say("异常：" + e.javaClass.name + " / " + e.message)
                e.stackTrace.take(5).forEach { say("   " + it.toString()) }
            }
        }, "probe").start()
    }
}
