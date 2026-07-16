plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.messagesender"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.messagesender"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // === License server configuration ===
        // SERVER_URL can be overridden at build time with -PserverUrl=...
        // (used for the permanent domain, e.g. https://sms.alfa-vpn.ru).
        val serverUrl = (project.findProperty("serverUrl") as String?)
            ?: "https://your-server.example.com"
        // The server's public key (stable as long as server/data/keys.json is
        // kept). Overridable with -PlicenseKey=...
        val licenseKey = (project.findProperty("licenseKey") as String?)
            ?: "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE+/8SgSzWA2a/NQu2uDH0vBgaY4M4VWtAnUPkkUXyw+VWeSxJVx7BCSzdPYthzC/LKr/+zi7xLBdnYzA60iqZEQ=="

        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
        buildConfigField("String", "LICENSE_PUBLIC_KEY", "\"$licenseKey\"")
    }

    buildTypes {
        debug {
            // Test build: licensing is bypassed so the app can be installed and
            // tried on your own phone without a running license server.
            buildConfigField("boolean", "LICENSE_ENFORCED", "false")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Distributable build: a valid token from your server is required.
            buildConfigField("boolean", "LICENSE_ENFORCED", "true")
            // Sign with the debug key so the release APK is installable when
            // distributed outside the Play Store (sideloaded).
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
