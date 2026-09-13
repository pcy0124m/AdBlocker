plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("kapt")
}

android {
    namespace = "com.example.adblocker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.adblocker"
        minSdk = 23
        targetSdk = 34
        // 重要：每次发版必须递增 versionCode，否则覆盖安装会被 Android 拒绝
        // （versionCode 不升，adb install -r / 安装器会报 VERSION_DOWNGRADE 而失败），
        // 旧版会一直留在用户手机上 —— 这正是「v0.1.10 已修但用户仍在用闪退版」的根因。
        versionCode = 11
        versionName = "0.1.11"
    }

    signingConfigs {
        create("release") {
            // 签名信息从 CI Secrets 注入的环境变量读取；本地不带 env 时不影响 debug 构建。
            // 注意：build 脚本里 file() 是相对模块目录（app/）解析的，所以这里用
            // rootProject.file() 把 KEYSTORE_PATH 统一按「仓库根目录」解析，避免拼出 app/app/xxx。
            val ksPath = System.getenv("KEYSTORE_PATH")
            if (!ksPath.isNullOrEmpty()) {
                storeFile = rootProject.file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 仅当 CI 注入了 KEYSTORE_PATH 时才对 release 包签名；本地无 keystore 时产出未签名包
            if (System.getenv("KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // 放大错误上限，避免一次只报前几个 unresolved 而把真正的根因错误截断
        freeCompilerArgs = freeCompilerArgs + listOf("-Xmax-errors=200")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Room (本地黑名单持久化)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
