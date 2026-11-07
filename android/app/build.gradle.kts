import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.moodify.app"
    compileSdk = 37

    val buildVersionCode = project.findProperty("versionCode")
        ?.toString()
        ?.toIntOrNull()
        ?: 1
    val buildVersionName = project.findProperty("versionName")
        ?.toString()
        ?.takeIf { it.isNotBlank() }
        ?: "1.0"

    defaultConfig {
        applicationId = "com.moodify.app"
        minSdk = 24
        targetSdk = 35
        versionCode = buildVersionCode
        versionName = buildVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // moodify-social server (Cloudflare Worker, social/). Override with -PsocialBaseUrl=…
        buildConfigField("String", "SOCIAL_BASE_URL", "\"${project.findProperty("socialBaseUrl") ?: "https://moodify-social.moodify-kp.workers.dev"}\"")

        // Firebase, for content-free wake-up pings only. Read from google-services.json
        // (no Google Services plugin needed); without the file, pings are off and the
        // 15-minute background check still delivers messages.
        val firebase = file("google-services.json").takeIf { it.exists() }
            ?.let { groovy.json.JsonSlurper().parse(it) as Map<*, *> }
        if (firebase != null) {
            val info = firebase["project_info"] as Map<*, *>
            val client = (firebase["client"] as List<*>).map { it as Map<*, *> }.first {
                ((it["client_info"] as Map<*, *>)["android_client_info"] as Map<*, *>)["package_name"] == "com.moodify.app"
            }
            resValue("string", "google_app_id", (client["client_info"] as Map<*, *>)["mobilesdk_app_id"].toString())
            resValue("string", "gcm_defaultSenderId", info["project_number"].toString())
            resValue("string", "google_api_key", ((client["api_key"] as List<*>).first() as Map<*, *>)["current_key"].toString())
            resValue("string", "project_id", info["project_id"].toString())
        }
    }

    // Release signing: android/keystore.properties locally (never committed), or
    // MOODIFY_KEYSTORE_* environment variables in CI. Without either, release
    // builds stay unsigned, as before.
    val keystoreProps = Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) file.inputStream().use(::load)
    }
    val signingStoreFile = keystoreProps.getProperty("storeFile")?.let { rootProject.file(it) }
        ?: System.getenv("MOODIFY_KEYSTORE_FILE")?.let(::file)
    val signingStorePassword = keystoreProps.getProperty("storePassword") ?: System.getenv("MOODIFY_KEYSTORE_PASSWORD")
    val signingKeyAlias = keystoreProps.getProperty("keyAlias") ?: System.getenv("MOODIFY_KEY_ALIAS")
    val signingKeyPassword = keystoreProps.getProperty("keyPassword") ?: System.getenv("MOODIFY_KEY_PASSWORD")
    val canSignRelease = signingStoreFile?.exists() == true &&
        !signingStorePassword.isNullOrBlank() && !signingKeyAlias.isNullOrBlank() && !signingKeyPassword.isNullOrBlank()

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = signingStoreFile
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // Debug builds talk to `npm run dev` in social/ on the host machine
            // (10.0.2.2 is the host from the Android emulator).
            buildConfigField("String", "SOCIAL_BASE_URL", "\"${project.findProperty("socialBaseUrl") ?: "http://10.0.2.2:8790"}\"")
        }
        release {
            if (canSignRelease) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
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
    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)

    // Coil
    implementation(libs.coil.compose)

    // Direct provider HTTP + local JSON persistence
    implementation(libs.okhttp.core)
    implementation(libs.bouncycastle.prov)
    implementation(libs.okhttp.logging)

    // Media3 ExoPlayer + MediaSessionService (background playback + notification)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.database)

    // Small local persistence layer for queue, favorites, and downloads.
    implementation(libs.gson)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.datastore.preferences)
    // Sandboxed local JavaScript runtime for YouTube player transformations.
    implementation(libs.quickjs.kt)

    // App shell: splash, type-safe navigation, artwork colours, background downloads.
    implementation(libs.core.splashscreen)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.palette.ktx)
    implementation(libs.work.runtime.ktx)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.zxing.core)
    implementation(libs.play.services.code.scanner)

    ksp(libs.room.compiler)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
