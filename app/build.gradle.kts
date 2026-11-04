plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
val repoName = System.getenv("GITHUB_REPOSITORY") ?: "OWNER/REPO"

android {
    namespace = "com.wifiradar.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.wifiradar.app"
        minSdk = 26
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.0.$runNumber"
        buildConfigField("String", "UPDATE_REPO", "\"$repoName\"")
    }
    buildFeatures { buildConfig = true }
    signingConfigs {
        create("main") {
            storeFile = file("wifiradar.jks")
            storePassword = "wifiradar123"
            keyAlias = "wifiradar"
            keyPassword = "wifiradar123"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("main") }
        getByName("release") {
            signingConfig = signingConfigs.getByName("main")
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
