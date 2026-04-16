plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace  = "com.anpaiss.karoobettereta"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.anpaiss.karoobettereta.v2"
        minSdk        = 23
        targetSdk     = 34
        versionCode   = 2
        versionName   = "2.0-dashboard"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.hammerhead.karoo.ext)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kxml2)  // XmlPullParser impl per test JVM (fuori Android)
}
