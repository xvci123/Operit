plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hermes.probe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hermes.probe"
        minSdk = 30
        targetSdk = 30
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters.add("arm64-v8a") }
        externalNativeBuild { cmake { arguments.add("-DANDROID_STL=c++_shared") } }
    }
    externalNativeBuild {
        // 直接复用主 App 里那份（构建时会由 :app:hermesFetchLibnode 生成）
        cmake { path = file("../app/src/main/cpp/hermes/CMakeLists.txt") }
    }
    buildTypes { getByName("release") { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// 引擎和源码由主模块那个任务生成，先跑它
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(":app:hermesFetchLibnode") }
