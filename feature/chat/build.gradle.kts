plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "me.rerere.fawntavern.feature.chat"
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
    api(project(":domain:chat"))

    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.runtime)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
