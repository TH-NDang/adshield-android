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
        versionCode = 11
        versionName = "1.0.0"
    }
}


dependencies {
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.81")
}
