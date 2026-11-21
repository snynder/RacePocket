plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
// The version people see (2.0, 2.1…) lives in version.txt; versionCode stays the build number so Android installs over it.
val appVersion = rootProject.file("version.txt").readText().trim()

android {
    namespace = "com.racepocket.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.racepocket.app"
        minSdk = 26
        targetSdk = 35
        versionCode = runNumber
        versionName = appVersion
        // "owner/repo" of the GitHub repository that builds the app, used by the in-app updater
        buildConfigField("String", "UPDATE_REPO", "\"${System.getenv("GITHUB_REPOSITORY") ?: ""}\"")
    }

    // The signing key comes from GitHub secrets (KEYSTORE_BASE64 + KEYSTORE_PASSWORD), never from the repository.
    // The workflow writes it to a temporary file and passes its path in KEYSTORE_FILE.
    val ksFile = System.getenv("KEYSTORE_FILE")?.let { file(it) }
    val ksPass = System.getenv("KEYSTORE_PASSWORD")
    signingConfigs {
        if (ksFile != null && ksFile.exists() && !ksPass.isNullOrEmpty()) {
            create("release") {
                storeFile = ksFile
                storePassword = ksPass
                keyAlias = "f1pocket" // alias stored inside the existing keystore (GitHub secret); changing it needs a new keystore
                keyPassword = ksPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // without the secrets (e.g. a build on your own PC) it falls back to the debug key
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
