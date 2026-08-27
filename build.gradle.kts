plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
}

val commonFeatureRules = listOf(
    Regex("^import me\\.rerere\\.fawntavern\\.R$"),
    Regex("^import me\\.rerere\\.fawntavern\\.di\\."),
    Regex("^import me\\.rerere\\.fawntavern\\.data\\.(character|preset|worldbook)\\..*Repository$"),
    Regex("^import me\\.rerere\\.fawntavern\\.data\\.settings\\..*Store$"),
)

tasks.register("checkArchitecture") {
    group = "verification"
    description = "检查模块源码是否违反单向依赖边界"

    doLast {
        val rules = mapOf(
            ":core:model" to listOf(
                Regex("^import android\\."),
                Regex("^import androidx\\."),
                Regex("^import me\\.rerere\\.fawntavern\\.ui\\."),
            ),
            ":core:extension" to listOf(
                Regex("^import android\\."),
                Regex("^import androidx\\."),
                Regex("^import me\\.rerere\\.fawntavern\\.ui\\."),
            ),
            ":domain:generation" to listOf(
                Regex("^import android\\."),
                Regex("^import androidx\\."),
                Regex("^import me\\.rerere\\.fawntavern\\.ui\\."),
                Regex("^import me\\.rerere\\.fawntavern\\.data\\.settings\\."),
            ),
            ":domain:chat" to listOf(
                Regex("^import android\\."),
                Regex("^import androidx\\.compose\\."),
                Regex("^import me\\.rerere\\.fawntavern\\.ui\\."),
                Regex("^import me\\.rerere\\.fawntavern\\.data\\.settings\\."),
            ),
            ":feature:chat" to listOf(
                Regex("^import me\\.rerere\\.fawntavern\\.R$"),
                Regex("^import me\\.rerere\\.fawntavern\\.di\\."),
                Regex("^import me\\.rerere\\.fawntavern\\.plugin\\."),
                Regex("^import me\\.rerere\\.fawntavern\\.data\\.settings\\..*Store$"),
                Regex("^import me\\.rerere\\.fawntavern\\.data\\.search\\."),
            ),
            ":feature:character" to commonFeatureRules,
            ":feature:preset" to commonFeatureRules,
            ":feature:worldbook" to commonFeatureRules,
        )
        val violations = mutableListOf<String>()
        rules.forEach { (path, forbidden) ->
            val sourceRoot = rootProject.project(path).projectDir.resolve("src/main")
            if (!sourceRoot.exists()) return@forEach
            sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                file.useLines { lines ->
                    lines.forEachIndexed { index, line ->
                        if (forbidden.any { it.containsMatchIn(line) }) {
                            violations += "${file.relativeTo(rootDir)}:${index + 1}: $line"
                        }
                    }
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException("架构边界检查失败：\n${violations.joinToString("\n")}")
        }
    }
}
