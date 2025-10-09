// Configure libcommon without declaring a redundant Android Gradle Plugin version so it inherits from the root build.
plugins {
    id("com.android.library")
}

android {
    namespace = "jp.ubiq.usbpreviewlenovo.libcommon"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        targetSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
