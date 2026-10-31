plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vipulkarada.delayfx"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vipulkarada.delayfx"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
}
