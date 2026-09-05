plugins {
    id("fawntavern.android.library")
}

android {
    namespace = "me.rerere.fawntavern.feature.api"
}

dependencies {
    api(project(":core:network"))

}
