plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.androidcast"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.androidcast"
        // Fire OS 5 (1st/2nd gen Fire TV Stick) is Android 5.1 = API 22.
        minSdk = 22
        // Deliberately kept at 28: it lets the app add Wi-Fi networks with
        // WifiManager.addNetwork() on every Fire OS version, and avoids the
        // Android 12+ runtime Bluetooth permissions. The app is sideloaded,
        // never published to a store, so the store target rules don't apply.
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sign release builds with the debug key so the APK can be
            // sideloaded straight away. Replace with your own key if you like.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
        abortOnError = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
