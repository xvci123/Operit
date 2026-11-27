package com.janeasystems.rn_nodejs_mobile

/**
 * Node 引擎入口（Hermes 自写最小壳）。
 * 包名与类名必须和桥源码里的 JNI 符号一致：
 * Java_com_janeasystems_rn_nodejs_mobile_RNNodeJsMobileModule_*
 */
class RNNodeJsMobileModule {

    companion object {
        init {
            System.loadLibrary("node")
            System.loadLibrary("nodejs-mobile-react-native-lib")
        }
    }

    external fun startNodeWithArguments(arguments: Array<String>): Int

    external fun sendMessageToNodeChannel(channelName: String, msg: String)

    external fun registerNodeDataDirPath(dataDir: String)

    external fun getCurrentABIName(): String
}
