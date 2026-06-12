plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.nebula.vpn"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rootvpn.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // The native Xray core (libgojni.so) ships for several ABIs inside
        // libv2ray.aar. Limit the packaged set to keep the APK reasonable.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            // R8 shrink + obfuscate, and strip unused resources → smaller Play upload.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // The Play Console accepts an .aab; split the native libs per ABI so each
    // device only downloads its own (the geoip/geosite data stays shared).
    bundle {
        abi { enableSplit = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // ── Xray / V2Ray native core ────────────────────────────────────────────
    // The real proxy engine: Xray-core exposed to Android via gomobile.
    // This libv2ray.aar (AndroidLibXrayLite v26.6.2) bundles the native core
    // (libgojni.so) plus geoip/geosite assets. Its CoreController handles the
    // tun device internally — no separate tun2socks library is required.
    implementation(files("libs/libv2ray.aar"))
}
