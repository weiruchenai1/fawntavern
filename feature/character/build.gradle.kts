plugins {
    id("fawntavern.android.library")
}

android {
    namespace = "me.rerere.fawntavern.feature.character"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:diagnostics"))
    implementation(project(":core:network"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.json)
}
