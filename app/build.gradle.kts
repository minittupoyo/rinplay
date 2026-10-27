plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "net.minittu.rinplay"
    compileSdk = 37

    defaultConfig {
        applicationId = "net.minittu.rinplay"
        minSdk = 26
        targetSdk = 37
        // リリースのワークフローが -PappVersionCode / -PappVersionName で渡す(なければ開発用の値)
        versionCode = (project.findProperty("appVersionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("appVersionName") as String?) ?: "0.1"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // リリースの署名。鍵はリポジトリに入れず、環境変数で渡す(GitHub Actions の Secrets)。
    // 渡さなければ、署名なしの APK になる。
    val keystorePath = System.getenv("RINPLAY_KEYSTORE")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("RINPLAY_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RINPLAY_KEY_ALIAS")
                keyPassword = System.getenv("RINPLAY_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.guava.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.jupnp)
    implementation(libs.jupnp.android)
    implementation(libs.jupnp.support)
    implementation(libs.slf4j.android)
}
