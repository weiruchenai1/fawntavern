plugins {
    id("fawntavern.android.library")
}

android {
    namespace = "me.rerere.fawntavern.feature.preset"
}

dependencies {
    api(project(":core:model"))
    testImplementation(libs.kotlinx.coroutines.core)
}
