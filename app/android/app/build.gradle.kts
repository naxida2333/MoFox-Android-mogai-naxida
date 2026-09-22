plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// ============ Release 签名配置 ============
// 优先读取仓库根目录的 key.properties（本地构建），不存在则用环境变量（GitHub Actions）。
// 两种方式都没有时，回退到 debug 签名（开发调试用）。
val keystoreProperties = java.util.Properties().apply {
    val localProps = rootProject.file("key.properties")
    if (localProps.exists()) {
        load(localProps.inputStream())
    }
}
val envStorePassword = System.getenv("KEYSTORE_PASSWORD")
val envKeyPassword = System.getenv("KEY_PASSWORD")
val envKeyAlias = System.getenv("KEY_ALIAS")
val envStoreFile = System.getenv("KEYSTORE_PATH")

android {
    namespace = "com.mofox.android"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.mofox.android"
        // jniLibs 由 Android 解压到 nativeLibraryDir，该目录由系统打 exec 标，
        // 与 targetSdk 等级下的 SELinux W^X 限制兼容。所以可以正常追到 35。
        minSdk = 24
        targetSdk = 35
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        ndk {
            // 只支持 arm64-v8a。32 位 ARM 装不了 napcat (Node.js)，x86 安卓没人用。
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=none")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    // proot 的 loader / libtalloc / sudo 等带特殊符号或 setuid 标记，被 Gradle 默认 strip
    // 后会立刻挂掉。doNotStrip 必须覆盖所有 ABI 的全部 .so。
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += listOf(
                "**/libmofoxpty.so",
                "**/libbash.so",
                "**/libbusybox.so",
                "**/libproot.so",
                "**/libsudo.so",
                "**/libloader.so",
                "**/liblibtalloc.so.2.so",
            )
        }
    }

    signingConfigs {
        create("release") {
            val storePwd = envStorePassword ?: keystoreProperties.getProperty("storePassword")
            val keyPwd   = envKeyPassword   ?: keystoreProperties.getProperty("keyPassword")
            val keyAl    = envKeyAlias      ?: keystoreProperties.getProperty("keyAlias") ?: "upload"
            val storeF   = envStoreFile      ?: keystoreProperties.getProperty("storeFile") ?: "upload-keystore.jks"

            if (!storePwd.isNullOrEmpty() && !keyPwd.isNullOrEmpty()) {
                storeFile = rootProject.file(storeF)
                storePassword = storePwd
                keyAlias = keyAl
                keyPassword = keyPwd
            }
        }
    }

    buildTypes {
        release {
            // 优先使用配置好的 release 签名，没有则回退到 debug
            signingConfig = if (signingConfigs.getByName("release").storeFile?.exists() == true) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}