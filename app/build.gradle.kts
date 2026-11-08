plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.retranslate"
    compileSdk = 36
    // AGP 8.7.3's lint-vital crashes parsing the SDK's build-tools 37.0.0
    // revision ("For input string: 37.0"); pin the 36.0.0 tools this
    // project was built and verified with (BUILD-ENV.md).
    buildToolsVersion = "36.0.0"

    lint {
        // The pin above does not stop AGP 8.7.3's lint-vital from crashing
        // on this sandbox SDK's 37.0 packages ("For input string: 37.0" in
        // lintVitalAnalyzeRelease, before any app lint check runs). The
        // §12 gate is the unit suite + functional flows, not lint; release
        // lint is disabled so the R8 build can complete. Re-enable when
        // AGP is next upgraded.
        checkReleaseBuilds = false
    }

    defaultConfig {
        applicationId = "app.retranslate"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "1.1.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            // P8: fresh-app keystore (release/retranslate-release.jks).
            // Passwords arrive via the environment at build time — the
            // password itself lives ONLY in release/KEYSTORE-README.txt,
            // never in this file, git, or logs.
            storeFile = rootProject.file("release/retranslate-release.jks")
            storePassword = System.getenv("RT_STORE_PASSWORD")
            keyAlias = System.getenv("RT_KEY_ALIAS") ?: "retranslate"
            keyPassword = System.getenv("RT_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests {
            // PDFBox-Android and android.util.Log touch Android stubs on the
            // JVM; default return values keep those paths inert in unit tests.
            isReturnDefaultValues = true
        }
    }
}

// Room schema export (PLAN.md §8.3): schemas are checked into the project so
// every future schema change ships a tested manual migration.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)

    // P1: provider hub stack (PLAN.md §2.3/§2.5). Retrofit is deliberately
    // NOT added: the free providers are multi-instance with dynamic base
    // URLs, so the single OkHttp stack carries them directly.
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)

    // P3: Room schema v1 (§8), streaming importers (§7), PDFBox (§2.4).
    // kxml2 is the same XmlPullParser implementation Android bundles.
    // The app uses it compile-only: devices supply it from the framework
    // (identical parser via XmlPullParserFactory), and bundling it made
    // R8 fail ("library class XmlResourceParser implements program class
    // org.xmlpull.v1.XmlPullParser"). JVM tests get the real jar.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.pdfbox.android)
    compileOnly(libs.kxml2)
    testImplementation(libs.kxml2)

    // P4: WorkManager foreground jobs (§7.4).
    implementation(libs.androidx.work.runtime.ktx)

    // v1.1.3 (§H): Google ML Kit on-device translation — the Offline
    // provider. Models download per language pair (~30 MB) and run
    // entirely on the phone; no key, no account, nothing leaves it.
    implementation(libs.mlkit.translate)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
