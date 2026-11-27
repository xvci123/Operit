package com.janeasystems.cdvnodejsmobile

/**
 * Node 引擎的入口（Hermes 自写的最小壳）。
 *
 * 为什么不直接用那个包里自带的 NodeJS.java：那份是 Cordova 插件，继承 CordovaPlugin、
 * 依赖 CordovaInterface 才能实例化。我们只要它的 native 方法，所以自己声明一份同包同名的类，
 * JNI 符号（Java_com_janeasystems_cdvnodejsmobile_NodeJS_*）就能照样对上。
 */
class NodeJS {

    companion object {
        init {
            System.loadLibrary("nodejs-mobile-cordova-native-lib")
            System.loadLibrary("node")
        }
    }

    external fun startNodeWithArguments(
            arguments: Array<String>,
            nodePath: String,
            redirectOutputToLogcat: Boolean
    ): Int

    external fun sendMessageToNodeChannel(channelName: String, msg: String)

    external fun registerNodeDataDirPath(dataDir: String)

    external fun getCurrentABIName(): String
}
