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
