// eta-core: the ride models shared by the BetterETA and boschRange apps —
// route and elevation polyline decoding, the segment pipeline, the learned
// and physics speed models, the ETA engine, surface tagging, way memory, the
// FIT reader and the small on-route / progress detectors. No Android UI, no
// preferences, no logging: those stay in each app. Extracted from app/ on
// 2026-10-13 as a pure move (same package `com.anpaiss.bettereta`, same
// sources); only the two functions the app calls lost their `internal`.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace  = "com.anpaiss.etacore"
    compileSdk = 34

    defaultConfig {
        minSdk = 23
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.hammerhead.karoo.ext)       // RouteMetrics reads the Karoo's climb list
    implementation(libs.kotlinx.coroutines.android) // Mutex in SegmentPipeline, Flow helpers in Extensions.kt

    testImplementation(libs.junit)
}
