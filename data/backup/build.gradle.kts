plugins {
    id("fawntavern.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "me.rerere.fawntavern.data.backup"
}

dependencies {
    implementation(project(":core:diagnostics"))
    implementation(project(":core:model"))
    implementation(project(":data:chat"))
    implementation(project(":data:resources"))
    implementation(project(":data:settings"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
