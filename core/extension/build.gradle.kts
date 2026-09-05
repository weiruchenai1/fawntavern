plugins {
    id("fawntavern.android.library")
}

android {
    namespace = "me.rerere.fawntavern.core.extension"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:network"))
}
