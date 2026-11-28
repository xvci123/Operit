package com.janeasystems.rn_nodejs_mobile

/** 加载顺序：emutls shim → c++_shared → node → 胶水 */
class EmutlsLoaderShim private constructor() {
    companion object {
        fun load() {
            runCatching { System.loadLibrary("hermes_emutls") }
        }
    }
}

/** 和主 App 里同名的桥入口（JNI 符号要这个包名+类名）。 */
class RNNodeJsMobileModule {
    external fun startNodeWithArguments(arguments: String): Int
    external fun sendMessageToNodeChannel(channelName: String, msg: String)
    external fun registerNodeDataDirPath(dataDir: String)
    external fun getCurrentABIName(): String
}
