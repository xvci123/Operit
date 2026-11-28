// Hermes 自己的 Node 点火代码（不依赖任何第三方桥）
#include <jni.h>
#include <string>
#include "node.h"

extern "C" JNIEXPORT jint JNICALL
Java_com_hermes_node_NodeLauncher_startNative(JNIEnv* env, jclass, jstring scriptPath) {
    const char* path = env->GetStringUTFChars(scriptPath, nullptr);
    std::string a0 = "node";
    std::string a1 = path ? path : "";
    char* argv[3];
    argv[0] = const_cast<char*>(a0.c_str());
    argv[1] = const_cast<char*>(a1.c_str());
    argv[2] = nullptr;
    if (path) env->ReleaseStringUTFChars(scriptPath, path);
    return node::Start(2, argv);
}
