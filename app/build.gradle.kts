import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

// Release identity comes from CI: the tag gives the version name, the run
// number the version code. A local build keeps fixed values.
val releaseVersion: String =
    System.getenv("RELEASE_VERSION")?.removePrefix("v")?.takeIf { it.isNotBlank() } ?: "1.0"
val buildNumber: Int = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 0
val appVersionCode: Int = if (buildNumber > 0) 100 + buildNumber else 3

// The keystore travels as a base64 secret and is decoded next to the module,
// outside build/ so `clean` cannot remove it mid-build. *.keystore is ignored.
val keystoreBase64: String? = System.getenv("KEYSTORE_BASE64")?.takeIf { it.isNotBlank() }

android {
    namespace  = "com.anpaiss.bettereta"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.anpaiss.bettereta"
        minSdk        = 23
        targetSdk     = 34
        versionCode   = appVersionCode
        versionName   = releaseVersion
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

// Karoo extension manifest: the JSON the Karoo fetches from MANIFEST_URL (see
// AndroidManifest.xml) to offer installs and updates from its extension
// library. Regenerated on every build; only a tagged CI release publishes it,
// next to the APK it points at. Schema: io.hammerhead.karooext.models.KarooAppManifest.
val generateManifest by tasks.registering {
    val out  = layout.projectDirectory.file("manifest.json")
    val repo = "https://github.com/anpaiss/BetterETA"
    val version = releaseVersion
    val code = appVersionCode
    val notes = System.getenv("RELEASE_NOTES").orEmpty()
    // Declared as inputs so a change of version/notes (they come from the
    // environment) re-runs the task; with outputs alone Gradle would keep a
    // stale manifest.json as up-to-date.
    inputs.property("version", version)
    inputs.property("code", code)
    inputs.property("notes", notes)
    outputs.file(out)
    doLast {
        fun q(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        val screenshots = listOf("dashboard.png", "app-info-1.png", "app-info-2.png")
            .joinToString(", ") { q("https://raw.githubusercontent.com/anpaiss/BetterETA/v$version/docs/$it") }
        out.asFile.writeText(
            """
            {
              "label": "BetterETA",
              "packageName": "com.anpaiss.bettereta",
              "latestApkUrl": ${q("$repo/releases/download/v$version/app-release.apk")},
              "latestVersion": ${q(version)},
              "latestVersionCode": $code,
              "developer": "Andrea Paissan",
              "description": ${q("Smarter ETA for the route you are navigating: learns how fast you ride at each gradient and surface and estimates every remaining segment from that, instead of a distance / average-speed guess.")},
              "releaseNotes": ${q(notes)},
              "screenshotUrls": [$screenshots],
              "tags": ["performance"]
            }
            """.trimIndent()
        )
    }
}
tasks.named("preBuild") { dependsOn(generateManifest) }

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
}
