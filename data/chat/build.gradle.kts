plugins {
    id("fawntavern.android.library")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "me.rerere.fawntavern.data.chat"
}

dependencies {
    api(project(":domain:chat"))
    api(project(":core:model"))
    api(libs.androidx.paging.runtime)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.robolectric)
}

ksp {
    arg("room.schemaLocation", file("schemas").absolutePath)
}
