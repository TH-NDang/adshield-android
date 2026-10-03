plugins {
    id("com.android.application")
}

android {
    namespace = "com.thndang.adshield"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.thndang.adshield"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "0.9.0"
    }
}


dependencies {
    implementation("androidx.webkit:webkit:1.16.0")
}
