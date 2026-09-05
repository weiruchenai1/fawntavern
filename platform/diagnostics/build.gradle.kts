plugins {
    id("fawntavern.android.library")
}

val appVersionName = providers.gradleProperty("versionName")
    .orElse(providers.environmentVariable("VERSION_NAME"))
    .orElse("0.1.0")
    .get()
val buglyAppId = providers.gradleProperty("buglyAppId")
    .orElse(providers.environmentVariable("BUGLY_APP_ID"))
    .orElse("")
    .get()

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "me.rerere.fawntavern.platform.diagnostics"
    defaultConfig {
        buildConfigField("String", "VERSION_NAME", buildConfigString(appVersionName))
        buildConfigField("String", "BUGLY_APP_ID", buildConfigString(buglyAppId))
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "FIREBASE_ENABLED", "false")
            buildConfigField("boolean", "BUGLY_ENABLED", "false")
        }
        release {
            buildConfigField("boolean", "FIREBASE_ENABLED", "true")
            buildConfigField("boolean", "BUGLY_ENABLED", "true")
        }
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:diagnostics"))
    implementation(files("libs/crashreport-4.1.9.3.aar"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.okhttp)

    val firebaseBom = platform(libs.firebase.bom)
    implementation(firebaseBom)
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)

}
