plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
}

tasks.register("checkArchitecture") {
    group = "verification"
    description = "检查模块源码是否违反单向依赖边界"

    inputs.files(
        fileTree("core/model/src/main") { include("**/*.kt") },
        fileTree("core/extension/src/main") { include("**/*.kt") },
        fileTree("domain/generation/src/main") { include("**/*.kt") },
        fileTree("domain/chat/src/main") { include("**/*.kt") },
        fileTree("feature/chat/src/main") { include("**/*.kt") },
        fileTree("feature/character/src/main") { include("**/*.kt") },
        fileTree("feature/preset/src/main") { include("**/*.kt") },
        fileTree("feature/worldbook/src/main") { include("**/*.kt") },
    ).withPropertyName("architectureSources").withPathSensitivity(PathSensitivity.RELATIVE)

    doLast {
        val commonFeatureRules = listOf(
            Regex("^import me\\.rerere\\.fawntavern\\.R$"),
            Regex("^import me\\.rerere\\.fawntavern\\.di\\."),
            Regex("^import me\\.rerere\\.fawntavern\\.data\\.(character|preset|worldbook)\\..*Repository$"),
            Regex("^import me\\.rerere\\.fawntavern\\.data\\.settings\\..*Store$"),
        )
        val rules = mapOf(
            "/core/model/" to listOf(
                Regex("^import android\\."),
                Regex("^import androidx\\."),
                Regex("^import me\\.rerere\\.fawntavern\\.ui\\."),
            ),
            "/core/extension/" to listOf(
                Regex("^import android\\."),
                Regex("^import androidx\\."),
                Regex("^import me\\.rerere\\.fawntavern\\.ui\\."),
            ),
            "/domain/generation/" to listOf(
                Regex("^import android\\."),
                Regex("^import androidx\\."),
                Regex("^import me\\.rerere\\.fawntavern\\.ui\\."),
                Regex("^import me\\.rerere\\.fawntavern\\.data\\.settings\\."),
            ),
            "/domain/chat/" to listOf(
                Regex("^import android\\."),
                Regex("^import androidx\\.compose\\."),
                Regex("^import me\\.rerere\\.fawntavern\\.ui\\."),
                Regex("^import me\\.rerere\\.fawntavern\\.data\\.settings\\."),
            ),
            "/feature/chat/" to listOf(
                Regex("^import me\\.rerere\\.fawntavern\\.R$"),
                Regex("^import me\\.rerere\\.fawntavern\\.di\\."),
                Regex("^import me\\.rerere\\.fawntavern\\.plugin\\."),
                Regex("^import me\\.rerere\\.fawntavern\\.data\\.settings\\..*Store$"),
                Regex("^import me\\.rerere\\.fawntavern\\.data\\.search\\."),
            ),
            "/feature/character/" to commonFeatureRules,
            "/feature/preset/" to commonFeatureRules,
            "/feature/worldbook/" to commonFeatureRules,
        )
        val violations = mutableListOf<String>()
        inputs.files.files.filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val normalizedPath = "/${file.invariantSeparatorsPath}"
            val forbidden = rules.entries.firstOrNull { (path, _) -> path in normalizedPath }?.value
                ?: return@forEach
            file.useLines { lines ->
                lines.forEachIndexed { index, line ->
                    if (forbidden.any { it.containsMatchIn(line) }) {
                        violations += "${file.invariantSeparatorsPath}:${index + 1}: $line"
                    }
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException("架构边界检查失败：\n${violations.joinToString("\n")}")
        }
    }
}
