plugins {
    id("fawntavern.android.library")
}

android {
    namespace = "me.rerere.fawntavern.data.settings"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:network"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.json)

    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
}
