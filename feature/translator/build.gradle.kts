plugins {
    id("fawntavern.android.library")
}

android {
    namespace = "me.rerere.fawntavern.feature.translator"
}

dependencies {
    api(project(":core:network"))
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
}
