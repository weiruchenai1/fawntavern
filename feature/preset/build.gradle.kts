plugins {
    id("fawntavern.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "me.rerere.fawntavern.feature.preset"
}

dependencies {
    api(project(":core:model"))
    testImplementation(libs.kotlinx.coroutines.core)
}
