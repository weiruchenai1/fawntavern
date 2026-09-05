plugins {
    id("fawntavern.android.library")
}

android {
    namespace = "me.rerere.fawntavern.domain.chat"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":domain:generation"))
    api(libs.androidx.paging.runtime)
    api(libs.kotlinx.coroutines.core)

}
