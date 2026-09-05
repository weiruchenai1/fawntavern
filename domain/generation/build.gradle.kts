plugins {
    id("fawntavern.android.library")
}

android {
    namespace = "me.rerere.fawntavern.domain.generation"
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.core)
}
