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
import java.util.zip.ZipInputStream
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


    /** 把 assets 里的依赖库复制到私有目录，返回该目录。 */
    private fun prepareLibs(): File {
        val dst = File(filesDir, "termux/lib").apply { mkdirs() }
        val names = assets.list("termux/lib").orEmpty()
        say("依赖库 ${names.size} 个")
        names.forEach { n ->
            val out = File(dst, n)
            if (!out.exists() || out.length() == 0L) {
                assets.open("termux/lib/$n").use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            }
        }
        return dst
    }

    /** 直接执行 Node 二进制，打印版本。 */
    private fun runNodeExe() {
        try {
            val libDir = prepareLibs()
            val exe = File(applicationInfo.nativeLibraryDir, "libnodebin.so")
            say("可执行文件：" + exe.absolutePath + " exists=" + exe.exists())
            val pb = ProcessBuilder(exe.absolutePath, "--version")
            pb.environment()["LD_LIBRARY_PATH"] = libDir.absolutePath + ":" + applicationInfo.nativeLibraryDir
            pb.environment()["HOME"] = filesDir.absolutePath
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val outText = proc.inputStream.bufferedReader().readText().trim()
            val code = proc.waitFor()
            say("退出码=$code")
            outText.split("\n").forEach { line ->
                var i = 0
                while (i < line.length) {
                    val end = minOf(i + 40, line.length)
                    say("  " + line.substring(i, end))
                    i = end
                }
            }
        } catch (e: Throwable) {
            say("执行异常：" + e.javaClass.simpleName + " / " + e.message)
        }
    }


    /** 解开 assets 里的 DSHA 核心包（只解一次）。 */
    private fun unzipDsh(): File {
        val dst = File(filesDir, "dsh")
        val marker = File(dst, "dsh/lib/bin.js")
        if (marker.exists()) { say("DSHA 已解开（跳过）"); return dst }
        say("解压 DSHA 核心包 ...")
        val t0 = System.currentTimeMillis()
        ZipInputStream(assets.open("dsh/dsh-core.zip")).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val out = File(dst, e.name)
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { o -> zis.copyTo(o) }
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        say("解压耗时 " + ((System.currentTimeMillis() - t0) / 1000) + " 秒，bin.js=" + marker.exists())
        return dst
    }

    /** 用 Node 26 跑 DSHA 的入口（先只看版本/帮助）。 */
    private fun runDsh(args: List<String>) {
        try {
            val libDir = prepareLibs()
            val exe = File(applicationInfo.nativeLibraryDir, "libnodebin.so")
            val root = unzipDsh()
            val bin = File(root, "dsh/lib/bin.js")
            val cmd = mutableListOf(exe.absolutePath, bin.absolutePath)
            cmd.addAll(args)
            say("执行：" + args.joinToString(" "))
            val pb = ProcessBuilder(cmd)
            pb.directory(root)
            pb.environment()["LD_LIBRARY_PATH"] = libDir.absolutePath + ":" + applicationInfo.nativeLibraryDir
            pb.environment()["HOME"] = filesDir.absolutePath
            pb.environment()["DSH_HOME"] = File(filesDir, "dsh-home").absolutePath
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val outText = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            say("DSH 退出码=" + code)
            outText.split("\n").take(60).forEach { line ->
                var i = 0
                while (i < line.length) {
                    val end = minOf(i + 40, line.length)
                    say("  " + line.substring(i, end)); i = end
                }
            }
        } catch (e: Throwable) {
            say("DSH 执行异常：" + e.javaClass.simpleName + " / " + e.message)
        }
    }


    /** 启动 DSHA 的 sdk-minimal 配置，走 JSON-RPC 握手并问一句。 */
    private fun runDshSession() {
        try {
            val libDir = prepareLibs()
            val exe = File(applicationInfo.nativeLibraryDir, "libnodebin.so")
            val root = File(filesDir, "dsh")
            val bin = File(root, "dsh/lib/bin.js")
            val work = File(filesDir, "workspace").apply { mkdirs() }
            val home = File(filesDir, "dsh-home").apply { mkdirs() }
            say("== 启动 DSHA 会话（sdk-minimal）==")
            val pb = ProcessBuilder(exe.absolutePath, bin.absolutePath, "--profile", "sdk-minimal")
            pb.directory(root)
            val env = pb.environment()
            env["LD_LIBRARY_PATH"] = libDir.absolutePath + ":" + applicationInfo.nativeLibraryDir
            env["HOME"] = filesDir.absolutePath
            env["DSH_HOME"] = home.absolutePath
            env["DEEPSEEK_API_KEY"] = System.getenv("HERMES_KEY") ?: "sk-placeholder"
            pb.redirectErrorStream(false)
            val proc = pb.start()
            val writer = proc.outputStream.bufferedWriter()
            val reader = proc.inputStream.bufferedReader()

            fun send(line: String) { writer.write(line + "\n"); writer.flush() }
            send("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"provider":"deepseek","model":"deepseek-chat","cwd":"${work.absolutePath}"}}""")

            val deadline = System.currentTimeMillis() + 30000
            var gotInit = false
            while (System.currentTimeMillis() < deadline) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                say("<< " + line.take(200))
                if (!gotInit && line.contains("\"id\":1")) {
                    gotInit = true
                    say("== 握手成功，发一句话 ==")
                    send("""{"jsonrpc":"2.0","id":2,"method":"session/prompt","params":{"sessionId":"probe-1","content":[{"type":"text","text":"用一句话自我介绍"}]}}""")
                }
                if (line.contains("\"id\":2")) { say("== 已投递 prompt ==") }
            }
            proc.destroy()
            say("== 会话演示结束 ==")
        } catch (e: Throwable) {
            say("会话异常：" + e.javaClass.simpleName + " / " + e.message)
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
        runNodeExe()
        Thread { runDsh(listOf("--version")); runDshSession() }.start()

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
