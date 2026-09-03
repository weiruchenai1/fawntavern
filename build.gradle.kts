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
        fileTree("core") { include("**/src/main/**/*.kt") },
        fileTree("domain") { include("**/src/main/**/*.kt") },
        fileTree("data") { include("**/src/main/**/*.kt") },
        fileTree("feature") { include("**/src/main/**/*.kt") },
        fileTree("platform") { include("**/src/main/**/*.kt") },
    ).withPropertyName("architectureSources").withPathSensitivity(PathSensitivity.RELATIVE)

    doLast {
        val allowedLayers = mapOf(
            "core" to setOf("core"),
            "domain" to setOf("core", "domain"),
            "data" to setOf("core", "domain", "data"),
            "feature" to setOf("core", "domain"),
            "platform" to setOf("core", "domain", "data"),
            "app" to setOf("core", "domain", "data", "feature", "platform"),
        )
        val projectDependency = Regex("""project\("(:[^"]+)"\)""")
        val graph = rootDir.walkTopDown()
            .filter { it.name == "build.gradle.kts" && it != rootProject.buildFile && "build" !in it.invariantSeparatorsPath.split('/') }
            .associate { file ->
                val module = ":" + file.parentFile.relativeTo(rootDir).invariantSeparatorsPath.replace('/', ':')
                module to projectDependency.findAll(file.readText()).map { it.groupValues[1] }.toList()
            }
        fun layer(module: String): String = module.removePrefix(":").substringBefore(':')
        val dependencyViolations = graph.flatMap { (source, dependencies) ->
            dependencies.mapNotNull { target ->
                val allowed = allowedLayers[layer(source)].orEmpty()
                "$source -> $target".takeIf { layer(target) !in allowed }
            }
        }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        val cycles = mutableListOf<String>()
        lateinit var visit: (String, List<String>) -> Unit
        visit = visit@{ node, trail ->
            if (node in visiting) {
                cycles += (trail + node).joinToString(" -> ")
                return@visit
            }
            if (node in visited) return@visit
            visiting += node
            graph[node].orEmpty().filter(graph::containsKey).forEach { visit(it, trail + node) }
            visiting -= node
            visited += node
        }
        graph.keys.forEach { visit(it, emptyList()) }
        if (dependencyViolations.isNotEmpty() || cycles.isNotEmpty()) {
            throw GradleException(
                "模块依赖图检查失败：\n" +
                    (dependencyViolations + cycles.map { "cycle: $it" }).joinToString("\n"),
            )
        }

        val commonFeatureRules = listOf(
            Regex("^import me\\.rerere\\.fawntavern\\.R$"),
            Regex("^import me\\.rerere\\.fawntavern\\.di\\."),
            Regex("^import me\\.rerere\\.fawntavern\\.data\\.(character|preset|worldbook)\\..*Repository$"),
            Regex("^import me\\.rerere\\.fawntavern\\.data\\.settings\\..*Store$"),
            Regex("^import me\\.rerere\\.fawntavern\\.data\\.api\\.(ChatApi|ModelApi|ConnectionTester|Http|ApiConfigStore)$"),
            Regex("^import me\\.rerere\\.fawntavern\\.data\\.search\\.createSearchService$"),
            Regex("^import me\\.rerere\\.fawntavern\\.data\\.backup\\.AppBackup$"),
        )
        val commonDataRules = listOf(
            Regex("^import me\\.rerere\\.fawntavern\\.R$"),
            Regex("^import me\\.rerere\\.fawntavern\\.di\\."),
            Regex("^import me\\.rerere\\.fawntavern\\.ui\\."),
            Regex("^import me\\.rerere\\.fawntavern\\.plugin\\."),
        )
        val commonPlatformRules = listOf(
            Regex("^import me\\.rerere\\.fawntavern\\.R$"),
            Regex("^import me\\.rerere\\.fawntavern\\.di\\."),
            Regex("^import me\\.rerere\\.fawntavern\\.ui\\."),
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
            "/feature/api/" to commonFeatureRules,
            "/feature/diagnostics/" to commonFeatureRules,
            "/feature/extension/" to commonFeatureRules,
            "/feature/regex/" to commonFeatureRules,
            "/feature/settings/" to commonFeatureRules,
            "/feature/statistics/" to commonFeatureRules,
            "/feature/translator/" to commonFeatureRules,
            "/data/backup/" to commonDataRules,
            "/data/chat/" to commonDataRules,
            "/data/generation/" to commonDataRules,
            "/data/resources/" to commonDataRules,
            "/data/search/" to commonDataRules,
            "/data/settings/" to commonDataRules,
            "/data/speech/" to commonDataRules,
            "/data/update/" to commonDataRules,
            "/platform/diagnostics/" to commonPlatformRules,
            "/platform/plugin/" to commonPlatformRules,
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
