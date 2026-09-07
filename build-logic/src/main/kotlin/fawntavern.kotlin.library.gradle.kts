import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}

val libraries = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    jvmToolchain(17)
    sourceSets {
        main { kotlin.srcDir("src/main/java") }
        test { kotlin.srcDir("src/test/java") }
    }
}

dependencies {
    add("testImplementation", libraries.findLibrary("junit").get())
}

// 保留全仓统一的测试入口，JVM 模块实际执行标准 test 任务。
tasks.register("testDebugUnitTest") {
    group = "verification"
    dependsOn(tasks.named("test"))
}
