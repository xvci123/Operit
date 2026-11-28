package com.ai.assistance.operit.core.plugins.node

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import com.hermes.node.NodeLauncher
import java.io.File

/**
 * Node 运行时：把引擎拉起来。
 *
 * 用自己写的入口（[NodeLauncher]），不依赖任何第三方桥——
 * 之前借 React Native 那套桥，它绑着整套框架的生命周期，崩了七八次也补不齐。
 *
 * 三条约束：
 *  - 后台线程启动（node::Start 会一直阻塞到 JS 结束）
 *  - 失败只记日志，绝不影响 App 正常使用
 *  - 只启动一次
 */
object HermesNodeRuntime {

    private const val TAG = "HermesNode"

    @Volatile private var started = false
    @Volatile private var statusText: String = "未启动"

    fun status(): String = statusText

    fun startAsync(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        Thread({
                    try {
                        val workDir = File(app.filesDir, "hermes/node")
                        if (!workDir.exists()) workDir.mkdirs()
                        val bootstrap = File(workDir, "bootstrap.js")
                        if (!bootstrap.exists()) {
                            app.assets.open("node/bootstrap.js").use { input ->
                                bootstrap.outputStream().use { out -> input.copyTo(out) }
                            }
                        }
                        statusText = "启动中"
                        val code = NodeLauncher.startNative(bootstrap.absolutePath)
                        statusText = "已结束（返回 $code）"
                        AppLogger.i(TAG, "Node 启动完成：$statusText")
                    } catch (e: Throwable) {
                        statusText = "启动失败：" + (e.message ?: e.javaClass.simpleName)
                        AppLogger.e(TAG, "Node 启动失败（已忽略，不影响 App）", e)
                    }
                }, "hermes-node")
                .start()
    }
}
