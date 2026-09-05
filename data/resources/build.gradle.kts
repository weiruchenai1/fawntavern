plugins {
    id("fawntavern.android.library")
}

android {
    namespace = "me.rerere.fawntavern.data.resources"
}

dependencies {
    implementation(project(":core:diagnostics"))
    api(project(":core:model"))
    api(libs.json)
    api(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
}
