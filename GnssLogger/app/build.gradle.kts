plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.gnsslogger"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.gnsslogger"
        minSdk = 24          // Galaxy S8 ships with Android 7.0, up to 9
        targetSdk = 28       // no runtime permission prompts needed on S8
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources { pickFirsts += listOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES") } }
}

dependencies {
    implementation("com.github.mik3y:usb-serial-for-android:3.7.3")
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    implementation("org.locationtech.proj4j:proj4j:1.3.0")
    implementation("org.locationtech.proj4j:proj4j-epsg:1.3.0")
}
