plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

fun readKeystoreProperties(): Map<String, String> {
    val file = rootProject.file("keystore.properties")
    if (!file.exists()) return emptyMap()
    return file.readLines()
        .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
        .map { it.split("=", limit = 2) }
        .filter { it.size == 2 }
        .associate { it[0].trim() to it[1].trim() }
}
val keystoreProps = readKeystoreProperties()

android {
    namespace = "me.rerere.fawntavern"
    compileSdk = 37

    defaultConfig {
        applicationId = "me.rerere.fawntavern"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        // keystore.properties 不入库，缺失时不创建该配置
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps["storeFile"]!!)
                storePassword = keystoreProps["storePassword"]!!
                keyAlias = keystoreProps["keyAlias"]!!
                keyPassword = keystoreProps["keyPassword"]!!
            }
        }
    }

    buildTypes {
        debug {
            // 与 release 并存安装：包名带 .debug 后缀 → 对 Android 是两个独立应用，数据目录
            // 互不影响，也不会因签名不同（debug/release keystore）撞 INSTALL_FAILED_UPDATE_INCOMPATIBLE。
            // manifest 的 FileProvider authority 用 ${applicationId}.fileprovider、代码里用
            // ctx.packageName，都会跟着后缀走，无需改动。
            applicationIdSuffix = ".debug"
        }
        release {
            // findByName 而非 getByName：没有 keystore.properties 时 release 保持未签名，
            // 不至于在配置阶段抛 "SigningConfig not found" 把所有任务（含 assembleDebug）带崩
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Material 3
    implementation(libs.androidx.material3)

    // Compose
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)

    // AppCompat (locale switching)
    implementation(libs.androidx.appcompat)

    // Lucide icons (used in Figma designs)
    implementation(libs.lucide.icons)

    // Markdown 渲染（纯 Compose）
    implementation(libs.markdown.renderer.m3)
    implementation(libs.reorderable)

    // 网络（SSE 流式 + 模型列表/余额查询）
    implementation(libs.okhttp)

    // 聊天记录存储：Room + Paging；alts 列用 kotlinx.serialization 编码
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
    implementation(libs.kotlinx.serialization.json)
    ksp(libs.androidx.room.compiler)
}
