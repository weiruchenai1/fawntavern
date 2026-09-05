plugins {
    id("fawntavern.android.library")
}

android {
    namespace = "me.rerere.fawntavern.feature.regex"
}

dependencies {
    api(project(":core:model"))
}
