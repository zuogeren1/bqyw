plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.zuogeren.bqyw"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.zuogeren.bqyw"
        minSdk = 29
        targetSdk = 36
        // 版本号跟 PC 版保持一致：1.1.3 -> 112
        versionCode = 112
        versionName = "1.1.3"
    }

    // 启动头要打版本号（BuildConfig.VERSION_NAME），别让它和 versionName 各写一份
    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 用调试签名：release 和 debug 的签名一致，才能直接覆盖装到已经装了
            // 调试版的手机上。换个签名就得先卸载，而卸载会把无障碍授权、抓屏授权
            // 和悬浮球位置一起清掉，纯粹给自己找麻烦。
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
