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
        // Bump versionCode on every release so the in-app updater detects it.
        // Override at build time: -PversionCode=2 -PversionName=1.1
        versionCode = ((project.findProperty("versionCode") as String?)?.toInt()) ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.0"

        // === License server configuration ===
        // SERVER_URL can be overridden at build time with -PserverUrl=...
        val serverUrl = (project.findProperty("serverUrl") as String?)
            ?: "https://sms.sensadog.ru"
        // The server's public key (stable as long as the server keeps its signing
        // key). Overridable with -PlicenseKey=...
        val licenseKey = (project.findProperty("licenseKey") as String?)
            ?: "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE/HJFDjQj/ChiGjXcjB80exLdAeJD1JJ4QW5D1jSlFYqpJrxy5dMHSYwvDT62UbJ3CPNzNkYm54DEDYbQ1Y3Nbw=="

        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
        buildConfigField("String", "LICENSE_PUBLIC_KEY", "\"$licenseKey\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file("alfa-release.jks")
            storePassword = "alfasms123"
            keyAlias = "alfa"
            keyPassword = "alfasms123"
        }
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
            // Sign with the stable release key so in-place updates work.
            signingConfig = signingConfigs.getByName("release")
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
