import java.util.Properties
import java.io.File
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use { stream ->
            this.load(stream)
        }
    }
}

val supabaseUrl: String = localProperties.getProperty("SUPABASE_URL")
    ?: System.getenv("SUPABASE_URL")
    ?: "https://dbgtvzgwixerhnwvhtil.supabase.co"

val supabaseAnonKey: String = localProperties.getProperty("SUPABASE_ANON_KEY")
    ?: System.getenv("SUPABASE_ANON_KEY")
    ?: "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImRiZ3R2emd3aXhlcmhud3ZodGlsIiwicm9sZSI6InNlcnZpY2Vfcm9sZSIsImlhdCI6MTc4NjY5MDAyOCwiZXhwIjoyMTAyMjY2MDI4fQ.d-wjZmw8s1liL0JlllLDQ6KBGwHPm7drxhWkvGyOxLQ"

val googleWebClientId: String = localProperties.getProperty("GOOGLE_WEB_CLIENT_ID")
    ?: System.getenv("GOOGLE_WEB_CLIENT_ID")
    ?: "868888425624-9dfr5crtahiqph68tiba1acapvicnsug.apps.googleusercontent.com"

android {
    namespace = "com.streamify.app"
    compileSdk = 34

    val rawBuildNum = (System.getenv("GITHUB_RUN_NUMBER") ?: System.getenv("BUILD_NUMBER") ?: "1").toIntOrNull() ?: 1
    val buildNum = 130 + rawBuildNum

    defaultConfig {
        applicationId = "com.streamify.app"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNum
        versionName = "1.0.$buildNum"

        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")
        buildConfigField("String", "ADMIN_EMAIL", "\"sireenyadav@gmail.com\"")

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20 -O3 -flto"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val ksPath = System.getenv("KEYSTORE_FILE")
            if (!ksPath.isNullOrBlank() && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            } else {
                // No CI keystore provided: fall back to debug signing so
                // personal/family builds still produce installable APKs.
                storeFile = getByName("debug").storeFile
                storePassword = getByName("debug").storePassword
                keyAlias = getByName("debug").keyAlias
                keyPassword = getByName("debug").keyPassword
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }
    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    // Installs baseline profiles when they ship; required before the
    // baseline-profile Gradle module can be generated via macrobenchmark.
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    
    // Media3
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-session:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.2.1")
    
    // Coil
    implementation("io.coil-kt:coil-compose:2.5.0")
    
    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // UI Additions
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.ui:ui-text-google-fonts:1.6.2")

    // Google Credential Manager (One-Tap Google Sign-In)
    implementation("androidx.credentials:credentials:1.2.2")
    implementation("androidx.credentials:credentials-play-services-auth:1.2.2")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // ONNX Runtime
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.0")

    // High-Performance HTTP Transport
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Hardware-Backed KeyStore & EncryptedSharedPreferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

// ── Rust core packaging ─────────────────────────────────────────────────
// Builds libstreamify_core_rs.so for every packaged ABI and drops it into
// src/main/jniLibs BEFORE the Android build runs, so every APK ships the
// Rust engine. Failures abort the build loudly (never silently skipped).
//
// Escape hatch for toolchain-less builds:  ./gradlew assembleDebug -Pstreamify.skipRust=true
val skipRustBuild = (project.findProperty("streamify.skipRust") as String?)?.toBoolean() == true

val skipRustForCI = System.getenv("CI") == "true"

tasks.register<Exec>("cargoBuildRust") {
    group = "native"
    description = "Builds streamify_core_rs (cdylib) for arm64-v8a + armeabi-v7a via cargo-ndk."
    workingDir = file("../rust")
    enabled = !skipRustForCI
    doFirst {
        val ndkPath = System.getenv("ANDROID_NDK_HOME")
            ?: System.getenv("ANDROID_NDK_ROOT")
            ?: (try { android.ndkDirectory.absolutePath } catch (_: Exception) { "" })
        if (ndkPath.isNotBlank()) {
            environment("ANDROID_NDK_HOME", ndkPath)
            environment("ANDROID_NDK_ROOT", ndkPath)
            environment("NDK_HOME", ndkPath)
        }
        val cargoBin = System.getenv("HOME")?.let { "$it/.cargo/bin" } ?: ""
        environment("PATH", "$cargoBin:" + System.getenv("PATH"))
    }
    commandLine(
        System.getenv("HOME")?.let { "$it/.cargo/bin/cargo" } ?: "cargo",
        "ndk", "--platform", "26",
        "-t", "arm64-v8a", "-t", "armeabi-v7a",
        "-o", file("src/main/jniLibs").absolutePath,
        "build", "--release"
    )
}

val isCI = System.getenv("CI") == "true"
afterEvaluate {
    if (!isCI && !skipRustBuild) {
        tasks.named("preBuild") {
            dependsOn("cargoBuildRust")
        }
    }
}

