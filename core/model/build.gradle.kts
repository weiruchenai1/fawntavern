plugins {
    id("fawntavern.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "me.rerere.fawntavern.core.model"
}

dependencies {
    api(libs.kotlinx.serialization.json)

}
