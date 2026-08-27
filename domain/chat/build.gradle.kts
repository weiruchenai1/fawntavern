plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "me.rerere.fawntavern.domain.chat"
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
    api(project(":core:model"))
    api(libs.androidx.paging.runtime)
    api(libs.kotlinx.coroutines.core)
}
