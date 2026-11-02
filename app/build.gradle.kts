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
        versionCode = 43
        versionName = "1.8.7"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.core:core-ktx:1.15.0")
}
