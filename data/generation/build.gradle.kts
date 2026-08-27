plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "me.rerere.fawntavern.data.generation"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":domain:generation"))
    api(project(":core:network"))
}
