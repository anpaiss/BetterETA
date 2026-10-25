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
        minSdk        = 26   // KarooSwitches (TYPE_APPLICATION_OVERLAY); il Karoo 2 è API 26
        // 28 (storage legacy): unica via per leggere le mappe offline del Karoo in
        // /sdcard/offline/maps su Android 12; karoo-routegraph, nella libreria
        // ufficiale Hammerhead, fa lo stesso.
        //noinspection ExpiredTargetSdkVersion
        targetSdk     = 28
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
        debug {
            // Firmata con la chiave di release quando l'ambiente la fornisce (stesse
            // variabili della release): il permesso a firma di KarooSwitches è già
            // dichiarato da PoiAhead e AutoLoop e Android rifiuta un secondo dichiarante
            // con firma diversa (INSTALL_FAILED_DUPLICATE_PERMISSION, 18/10). Resta
            // debuggable: run-as per salvare e ripristinare modello e memoria tratti.
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    testOptions {
        // SurfaceReplayTest drives the surface classifier with a stub Context
        // (android.jar methods return defaults instead of throwing).
        unitTests.isReturnDefaultValues = true
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
        val screenshots = listOf("app-home.png", "app-model.png", "switches_mta_set.png")
            .joinToString(", ") { q("https://raw.githubusercontent.com/anpaiss/BetterETA/v$version/docs/$it") }
        out.asFile.writeText(
            """
            {
              "label": "BetterETA",
              "packageName": "com.anpaiss.bettereta",
              "latestApkUrl": ${q("$repo/releases/latest/download/BetterETA.apk")},
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
    implementation(project(":eta-core"))     // ride models shared with boschRange
    implementation(libs.hammerhead.karoo.ext)
    implementation("com.github.anpaiss:karooswitches:0.2.1")   // campo Switches + MTA regolabile dal popup
    implementation(libs.mapsforge.map.reader)   // fondo dalle mappe offline del Karoo (file Mapsforge)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
