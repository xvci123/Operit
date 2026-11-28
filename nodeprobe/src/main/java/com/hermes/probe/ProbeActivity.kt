package com.hermes.probe

import android.app.Activity
import android.os.Bundle
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 只干一件事：试着把 Node 引擎加载并启动起来，把结果写进 Download/probe-node.log。 */
class ProbeActivity : Activity() {

    companion object {
        // 桥内部持弱引用指向这个对象：必须留一个强引用，否则被 GC 后 JNI 直接崩
        @Volatile var keepAlive: Any? = null
    }

    private fun log(line: String) {
        Log.i("PROBE", line)   // 系统日志：一定看得到
        try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Operit")
            if (!dir.exists()) dir.mkdirs()
            val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            File(dir, "probe-node.log").appendText("$ts  $line\n")
        } catch (_: Throwable) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log("== probe 启动 ==")
        Thread({
            try {
                log("加载 c++_shared / emutls / node / 自己的入口 ...")
                System.loadLibrary("c++_shared")
                runCatching { System.loadLibrary("hermes_emutls") }
                System.loadLibrary("node")
                System.loadLibrary("hermes_node_jni")
                log("库全部加载成功")
                val workDir = java.io.File(filesDir, "node").apply { mkdirs() }
                val bootstrap = java.io.File(workDir, "bootstrap.js")
                bootstrap.writeText("console.log('### HERMES-NODE alive ' + process.version + ' ' + process.platform);\n")
                log("脚本：" + bootstrap.absolutePath)
                val code = com.hermes.node.NodeLauncher.startNative(bootstrap.absolutePath)
                log("startNative 返回：" + code)
            } catch (e: Throwable) {
                log("异常：" + e.javaClass.name + " / " + e.message)
                e.stackTrace.take(6).forEach { log("    at $it") }
            }
        }, "probe").start()
    }
}
