plugins {
    id("fawntavern.kotlin.library")
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.json)
}
