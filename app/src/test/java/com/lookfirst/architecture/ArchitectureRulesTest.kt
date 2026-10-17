package com.lookfirst.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CLAUDE.md architecture standards, checked by text search over `app/src/main` (comments removed), like
 * [IntentLaunchRulesTest]: pure domain, composables without view models (except routes), one-shot events by
 * `Channel`, read-only `StateFlow` from every view model.
 */
class ArchitectureRulesTest {
    private fun mainSources(): Map<String, String> {
        val root = File("src/main/java")
        check(root.isDirectory) { "run from the app module: ${File(".").absolutePath}" }
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.associate { file ->
            file.relativeTo(File(root, "com/lookfirst")).invariantSeparatorsPath to withoutComments(file.readText())
        }
    }

    private fun withoutComments(text: String): String =
        text.replace(Regex("""(?s)/\*.*?\*/"""), "").replace(Regex("""(?m)^\s*//.*$|\s//\s.*$"""), "")

    private fun layer(prefix: String): Map<String, String> {
        val files = mainSources().filterKeys { it.startsWith(prefix) }
        assertTrue("no source under $prefix", files.isNotEmpty())
        return files
    }

    private fun offendersOf(files: Map<String, String>, pattern: Regex): Set<String> =
        files.filterValues { pattern.containsMatchIn(it) }.keys

    @Test
    fun domainImportsNoAndroidClass() {
        val pattern = Regex("""(?m)^\s*import\s+(?:android|androidx|com\.google\.android)\.""")
        assertEquals(emptySet<String>(), offendersOf(layer("domain/"), pattern))
    }

    @Test
    fun domainDoesNotDependOnDataOrUi() {
        val pattern = Regex("""\bcom\.lookfirst\.(?:data|ui)\b""")
        assertEquals(emptySet<String>(), offendersOf(layer("domain/"), pattern))
    }

    @Test
    fun onlyRoutesAndTheNavHostTakeAViewModelParameter() {
        val composable =
            Regex("""@Composable\s+(?:(?:private|internal|public)\s+)?fun\s+(\w+)\s*\(([^{=]*?)\)\s*[{=:]""")
        val viewModelParameter = Regex(""":\s*\w*ViewModel\b""")
        val allowed = setOf("LookFirstNavHost")
        val offenders = mutableSetOf<String>()
        var routes = 0
        for ((path, text) in layer("ui/")) {
            for (match in composable.findAll(text)) {
                val (name, params) = match.destructured
                if (!viewModelParameter.containsMatchIn(params)) continue
                if (name.endsWith("Route")) {
                    routes++
                } else if (name !in allowed) {
                    offenders += "$path:$name"
                }
            }
        }
        assertTrue("the heuristic found no Route taking a view model", routes >= 3)
        assertEquals(emptySet<String>(), offenders)
    }

    @Test
    fun uiNeverUsesSharedFlowForEvents() {
        assertEquals(emptySet<String>(), offendersOf(layer("ui/"), Regex("""\b(?:Mutable)?SharedFlow\b""")))
    }

    @Test
    fun everyViewModelExposesItsStateAsAReadOnlyStateFlow() {
        val viewModels = layer("ui/").filterValues { Regex(""":\s*ViewModel\(\)""").containsMatchIn(it) }
        assertTrue("no view model found", viewModels.size >= 3)
        val exposed = Regex("""val\s+state\s*:\s*StateFlow<\w+>\s*=\s*\w+\.asStateFlow\(\)""")
        assertEquals(emptySet<String>(), viewModels.filterValues { !exposed.containsMatchIn(it) }.keys)
        val publicMutable = Regex("""(?m)^\s*(?:val|var)\s+\w+\s*(?::[^=\n]*)?=\s*Mutable(?:State)?Flow""")
        assertEquals(emptySet<String>(), offendersOf(viewModels, publicMutable))
    }
}
