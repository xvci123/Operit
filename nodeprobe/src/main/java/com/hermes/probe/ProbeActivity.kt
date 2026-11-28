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
                log("加载 emutls shim ...")
                runCatching { System.loadLibrary("hermes_emutls") }
                log("加载 c++_shared ...")
                System.loadLibrary("c++_shared")
                log("加载 node ...")
                System.loadLibrary("node")
                log("加载胶水 ...")
                System.loadLibrary("nodejs-mobile-react-native-native-lib")
                log("三个库都加载成功")

                val workDir = File(filesDir, "node").apply { mkdirs() }
                val bootstrap = File(workDir, "bootstrap.js")
                bootstrap.writeText(
                    "console.log('### PROBE-NODE alive ' + process.version);\n"
                )
                val cls = Class.forName("com.janeasystems.rn_nodejs_mobile.RNNodeJsMobileModule")
                val obj = cls.getDeclaredConstructor().newInstance()
                keepAlive = obj
                // 先把工作目录告诉它，再启动（主 App 里有那一步，探头之前漏了）
                runCatching {
                    cls.getMethod("registerNodeDataDirPath", String::class.java)
                        .invoke(obj, workDir.absolutePath)
                    log("已注册工作目录：" + workDir.absolutePath)
                }.onFailure { log("注册工作目录失败：" + it.message) }

                val started = cls.methods.firstOrNull {
                    it.name.startsWith("startNode") && it.parameterTypes.size == 1
                }?.invoke(obj, "node " + bootstrap.absolutePath)
                log("startNode 返回：$started")
            } catch (e: Throwable) {
                log("异常：" + e.javaClass.name + " / " + e.message)
                e.stackTrace.take(6).forEach { log("    at $it") }
            }
        }, "probe").start()
    }
}
