plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.looplooptech.audioscript"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.looplooptech.audioscript"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.7.3-android.1"

        // 只打包手机真实在用的 ABI：sherpa-onnx 的 x86/x86_64 是给模拟器用的，
        // 带上会让 APK 多出 70 MB 以上。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // 侧载分发，自定义签名由本地 keystore 完成；这里先用 debug 签名保证可安装。
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // ONNX 模型不要二次压缩：首次启动解包更快，也避免 aapt 处理 30 MB 级资产。
    androidResources {
        noCompress += listOf("onnx")
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/*.kotlin_module",
            )
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // sherpa-onnx 官方预编译 AAR（与桌面端 Rust crate 1.13 同版本、同一推理引擎）
    implementation(files("libs/sherpa-onnx-1.13.0.aar"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // ModelManager 支持官方 tar.bz2 归档；Android 只带 gzip/zip，需要 bzip2 + tar。
    implementation("org.apache.commons:commons-compress:1.26.2")

    // 离线翻译：ML Kit 本地翻译模型（首次下载后完全离线推理）
    implementation("com.google.mlkit:translate:17.0.3")

    testImplementation("junit:junit:4.13.2")
}
