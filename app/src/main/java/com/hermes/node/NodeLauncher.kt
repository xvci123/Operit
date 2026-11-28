package com.hermes.node

/** 点火：把 Node 引擎跑起来（自己写的入口，不依赖第三方桥）。 */
object NodeLauncher {
    init {
        runCatching { System.loadLibrary("c++_shared") }
        runCatching { System.loadLibrary("hermes_emutls") }
        System.loadLibrary("node")
        System.loadLibrary("hermes_node_jni")
    }

    /** 阻塞：一直跑到 Node 退出。必须放在后台线程调用。 */
    external fun startNative(scriptPath: String): Int
}
