plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.moc.corridaboa"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.moc.corridaboa"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "1.6.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
