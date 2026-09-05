import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("com.android.library")
}

val libraries = extensions.getByType<VersionCatalogsExtension>().named("libs")

android {
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    add("testImplementation", libraries.findLibrary("junit").get())
}
