plugins {
    id("fawntavern.android.library")
}

android {
    namespace = "me.rerere.fawntavern.feature.settings"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:network"))
    api(libs.kotlinx.coroutines.core)

}
