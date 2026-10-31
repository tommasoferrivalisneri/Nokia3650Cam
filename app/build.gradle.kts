plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.nokiacam"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.nokiacam"
        minSdk = 29          // MediaStore scoped: nessun permesso di storage
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
