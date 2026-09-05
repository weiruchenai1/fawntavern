plugins {
    id("fawntavern.android.library")
}

android {
    namespace = "me.rerere.fawntavern.feature.chat"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:extension"))
    api(project(":domain:chat"))
    implementation(project(":core:diagnostics"))
    implementation(project(":core:network"))
    implementation(project(":domain:generation"))
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.json)

    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.runtime)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.robolectric)
}
