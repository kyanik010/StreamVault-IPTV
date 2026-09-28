plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.streamvault.plugin.audiosource"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.streamvault.plugin.audiosource"
        minSdk = 25
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.datasource.okhttp)
}
