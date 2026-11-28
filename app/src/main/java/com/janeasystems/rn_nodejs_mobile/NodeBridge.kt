package com.janeasystems.rn_nodejs_mobile

/**
 * Node 引擎入口（Hermes 自写最小壳）。
 * 包名与类名必须和桥源码里的 JNI 符号一致：
 * Java_com_janeasystems_rn_nodejs_mobile_RNNodeJsMobileModule_*
 */
class RNNodeJsMobileModule {

    companion object {
        init {
            // 顺序要紧：先把 C++ 运行库拉进来，否则 libnode.so 里的
            // __emutls_get_address 解析不到（UnsatisfiedLinkError）
            runCatching { System.loadLibrary("c++_shared") }
            runCatching { System.loadLibrary("hermes_emutls") }
            System.loadLibrary("node")
            System.loadLibrary("nodejs-mobile-react-native-lib")
        }
    }

    external fun startNodeWithArguments(arguments: String): Int

    external fun sendMessageToNodeChannel(channelName: String, msg: String)

    external fun registerNodeDataDirPath(dataDir: String)

    external fun getCurrentABIName(): String
}
