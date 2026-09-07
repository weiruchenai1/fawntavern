plugins {
    id("fawntavern.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "me.rerere.fawntavern.feature.worldbook"
}

dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
}
