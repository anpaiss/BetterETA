import java.util.Base64

// boschRange: predicted Bosch e-bike battery at arrival. Separate app from
// BetterETA (Andrea, 13/10/2026): shares eta-core, exchanges nothing at runtime.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

val releaseVersion: String =
    System.getenv("RELEASE_VERSION")?.removePrefix("v")?.takeIf { it.isNotBlank() } ?: "0.1"
val buildNumber: Int = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 0
val appVersionCode: Int = if (buildNumber > 0) 100 + buildNumber else 1

val keystoreBase64: String? = System.getenv("KEYSTORE_BASE64")?.takeIf { it.isNotBlank() }

android {
    namespace  = "com.anpaiss.boschrange"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.anpaiss.boschrange"
        // 26: TYPE_APPLICATION_OVERLAY for the popups. targetSdk 28 (legacy
        // storage) like BetterETA: the only way to read the Karoo offline maps
        // in /sdcard/offline/maps for the surface classification.
        minSdk        = 26
        //noinspection ExpiredTargetSdkVersion
        targetSdk     = 28
        versionCode   = appVersionCode
        versionName   = releaseVersion
    }

    buildFeatures {
        buildConfig = true
    }

    if (keystoreBase64 != null) {
        signingConfigs {
            create("release") {
                val ksFile = layout.projectDirectory.file(".release.keystore").asFile
                ksFile.writeBytes(Base64.getDecoder().decode(keystoreBase64))
                storeFile     = ksFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias      = System.getenv("KEY_ALIAS")
                keyPassword   = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":eta-core"))
    implementation(libs.hammerhead.karoo.ext)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    // Real org.json for the unit tests: the mockable android.jar stubs it out.
    testImplementation("org.json:json:20240303")
}
