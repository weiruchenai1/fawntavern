plugins {
    id("fawntavern.android.library")
}

android {
    namespace = "me.rerere.fawntavern.data.generation"
}

dependencies {
    api(project(":domain:generation"))
    api(project(":core:network"))
}
