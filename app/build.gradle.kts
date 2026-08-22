import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseSigningPropertiesFile = providers
    .gradleProperty("tubenext.releaseSigningPropertiesFile")
    .orNull
    ?.let(rootProject::file)
    ?: rootProject.file("key.properties")
val keyProperties = Properties().apply {
    val keyFile = releaseSigningPropertiesFile
    if (keyFile.exists()) {
        keyFile.inputStream().use { load(it) }
    }
}
val releaseSigningPropertyNames = listOf(
    "storeFile",
    "storePassword",
    "keyAlias",
    "keyPassword"
)
val hasCompleteReleaseSigningProperties = releaseSigningPropertyNames.all { name ->
    keyProperties.getProperty(name)?.isNotBlank() == true
}
val hasProductionReleaseSigning = hasCompleteReleaseSigningProperties &&
    file(keyProperties.getProperty("storeFile").orEmpty()).isFile
val progressDiagnosticsEnabled = providers
    .gradleProperty("tubenext.enableProgressDiagnostics")
    .map(String::toBoolean)
    .getOrElse(false)

val verifyProductionReleaseSigning = tasks.register("verifyProductionReleaseSigning") {
    group = "verification"
    description = "Fails unless complete production release signing is configured."
    doLast {
        if (!hasProductionReleaseSigning) {
            throw GradleException(
                "Production release signing is unavailable. Configure all required " +
                    "key.properties values and a readable keystore, or build localRelease " +
                    "for an explicitly debug-signed release-like APK."
            )
        }
    }
}

android {
    namespace = "de.shakie.tubenext"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.shakie.tubenext"
        minSdk = 29
        targetSdk = 35
        versionCode = 24
        versionName = "1.4.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appLabel"] = "Tube NEXT"
        manifestPlaceholders["youtubeLinkHandlingEnabled"] = "true"
        buildConfigField("boolean", "YOUTUBE_LINK_HANDLING_ENABLED", "true")
        buildConfigField("boolean", "PROGRESS_DIAGNOSTICS_ENABLED", "false")
    }

    buildFeatures {
        buildConfig = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    signingConfigs {
        if (hasProductionReleaseSigning) {
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
            manifestPlaceholders["youtubeLinkHandlingEnabled"] = "false"
            buildConfigField("boolean", "YOUTUBE_LINK_HANDLING_ENABLED", "false")
        }

        release {
            // GeckoView can crash in native startup code when this release is
            // minified on real arm64 devices. Keep release builds unminified until
            // we can validate a Gecko/R8 rule set on hardware.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (hasProductionReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                null
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        if (progressDiagnosticsEnabled) {
            create("diagnosticRelease") {
                initWith(getByName("release"))
                versionNameSuffix = "-diagnostic"
                manifestPlaceholders["appLabel"] = "Tube NEXT Diagnose"
                buildConfigField("boolean", "PROGRESS_DIAGNOSTICS_ENABLED", "true")
                matchingFallbacks += listOf("release")
            }
        }

        create("localRelease") {
            initWith(getByName("release"))
            applicationIdSuffix = ".local"
            versionNameSuffix = "-local"
            manifestPlaceholders["appLabel"] = "Tube NEXT Local Release"
            manifestPlaceholders["youtubeLinkHandlingEnabled"] = "false"
            buildConfigField("boolean", "YOUTUBE_LINK_HANDLING_ENABLED", "false")
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("org.mozilla.geckoview:geckoview:152.0.20260706120035")
    testImplementation("junit:junit:4.13.2")
}

tasks.configureEach {
    if (name == "preReleaseBuild" || name == "preDiagnosticReleaseBuild") {
        dependsOn(verifyProductionReleaseSigning)
    }
}
