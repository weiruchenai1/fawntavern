plugins {
    alias(libs.plugins.android.library)
}

val appVersionName = providers.gradleProperty("versionName")
    .orElse(providers.environmentVariable("VERSION_NAME"))
    .orElse("0.1.0")
    .get()

android {
    namespace = "me.rerere.fawntavern.platform.plugin"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        buildConfigField("String", "APP_VERSION", "\"${appVersionName.replace("\"", "\\\"")}\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }
}

dependencies {
    api(project(":core:extension"))
    implementation(project(":core:diagnostics"))
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":domain:generation"))
    implementation(project(":data:chat"))
    implementation(project(":data:settings"))
    implementation(project(":data:update"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.json)
    implementation(libs.quickjs.kt)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.core)
}

configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.github.dokar3:quickjs-kt-android"))
            .using(module("io.github.dokar3:quickjs-kt-jvm:${libs.versions.quickjsKt.get()}"))
    }
}
