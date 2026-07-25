import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keyProperties = Properties().apply {
    val keyFile = rootProject.file("key.properties")
    if (keyFile.exists()) {
        keyFile.inputStream().use { load(it) }
    }
}
val hasCustomReleaseSigning = keyProperties.getProperty("storeFile")?.isNotBlank() == true

android {
    namespace = "de.shakie.tubenext"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.shakie.tubenext"
        minSdk = 29
        targetSdk = 35
        versionCode = 15
        versionName = "1.3.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appLabel"] = "Tube NEXT"
    }

    buildFeatures {
        buildConfig = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = false
        }
    }

    signingConfigs {
        if (hasCustomReleaseSigning) {
            create("release") {
                storeFile = file(keyProperties.getProperty("storeFile"))
                storePassword = keyProperties.getProperty("storePassword")
                keyAlias = keyProperties.getProperty("keyAlias")
                keyPassword = keyProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "Tube NEXT Debug"
        }

        release {
            // GeckoView can crash in native startup code when this release is
            // minified on real arm64 devices. Keep release builds unminified until
            // we can validate a Gecko/R8 rule set on hardware.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (hasCustomReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                // Fallback keeps local release builds possible before a private key exists.
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("org.mozilla.geckoview:geckoview:152.0.20260706120035")
}
