package com.ai.assistance.operit.core.plugins.node

import android.content.Context
import android.os.Environment
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Node 运行时（桥的最后一段：把引擎真正拉起来）。
 *
 * 前面几步已经解决了：引擎 `libnode.so` 进包、C++ 胶水编出来、桥源码齐全。
 * 这里只做三件事：
 *  1. 把 assets 里的引导脚本拷到私有目录（Node 要按文件路径读它）；
 *  2. 告诉 Node 它的工作目录；
 *  3. 起一个后台线程把它启动起来。
 *
 * 三条设计约束：
 *  - **启动失败不能影响 App**：全部包在 try/catch 里，只打日志，绝不抛到主线程。
 *  - **只启动一次**：进程内单例闸门；重复调用直接返回。
 *  - **不阻塞首屏**：放在独立线程里跑，冷启动动画不受影响。
 */
object HermesNodeRuntime {

    private const val TAG = "HermesNode"
    private const val BRIDGE_CLASS = "com.janeasystems.rn_nodejs_mobile.RNNodeJsMobileModule"

    @Volatile private var started = false
    /** 桥内部持弱引用指向它，必须留强引用，否则被 GC 后 JNI 崩溃 */
    @Volatile private var bridgeRef: Any? = null
    @Volatile private var statusText: String = "未启动"

    fun status(): String = statusText

    /** 写一行给人看的日志（Download/Operit/hermes-node.log），不靠 logcat 也能查。 */
    private fun fileLog(context: Context, line: String) {
        try {
            val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "Operit"
            )
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "hermes-node.log")
            val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            f.appendText("$ts  $line\n")
        } catch (e: Throwable) {
            AppLogger.w(TAG, "写 hermes-node.log 失败：" + e.message)
        }
    }

    /** 后台启动；多次调用只生效一次。 */
    fun startAsync(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        Thread({
                    fileLog(app, "== 开始启动 Node ==")
                    try {
                        startBlocking(app)
                        fileLog(app, "启动结果：" + statusText)
                    } catch (e: Throwable) {
                        statusText = "启动失败：" + (e.message ?: e.javaClass.simpleName)
                        fileLog(app, "启动异常：" + e.javaClass.name + " / " + e.message)
                        e.stackTrace.take(8).forEach { fileLog(app, "    at $it") }
                        AppLogger.e(TAG, "Node 启动失败（已忽略，不影响 App）", e)
                    }
                }, "hermes-node")
                .start()
    }

    private fun startBlocking(context: Context) {
        val workDir = File(context.filesDir, "hermes/node")
        if (!workDir.exists()) workDir.mkdirs()
        val bootstrap = File(workDir, "bootstrap.js")
        context.assets.open("node/bootstrap.js").use { input ->
            bootstrap.outputStream().use { out -> input.copyTo(out) }
        }
        fileLog(context, "引导脚本已就位：" + bootstrap.absolutePath + " (" + bootstrap.length() + " B)")

        fileLog(context, "工作目录：" + workDir.absolutePath)
        val cls = Class.forName(BRIDGE_CLASS)
        fileLog(context, "找到桥类：" + cls.name)
        val bridge = cls.getDeclaredConstructor().newInstance()
        bridgeRef = bridge

        // 1) 工作目录
        runCatching {
            cls.getMethod("registerNodeDataDirPath", String::class.java)
                .invoke(bridge, workDir.absolutePath)
        }

        // 2) 启动（参数：node 可执行名 + 脚本路径）
        val args = "node " + bootstrap.absolutePath
        val method =
            cls.methods.firstOrNull {
                it.name.startsWith("startNode") && it.parameterTypes.size == 1
            }
                ?: error("找不到启动方法（桥的接口和预期不一致）")
        val ret = method.invoke(bridge, args)
        statusText = "已启动（返回 " + (ret?.toString() ?: "?") + "）"
        fileLog(context, "startNode 返回：" + (ret?.toString() ?: "null"))
        AppLogger.i(TAG, "Node 已启动：$statusText，脚本 ${bootstrap.absolutePath}")
    }
}
