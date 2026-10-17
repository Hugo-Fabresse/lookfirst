package com.lookfirst.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Threat model T-SCH-8 / ADR-0003: only [com.lookfirst.data.platform.ActionExecutor] starts an Intent from scanned
 * content. Reads the sources of `app/src/main` (comments removed) and compares each risky call with an explicit
 * allow-list; a new file that needs one of them must be added here on purpose, in review.
 */
class IntentLaunchRulesTest {
    private class Rule(val name: String, val pattern: Regex, val allowed: Set<String>)

    private val rules = listOf(
        Rule(
            "starts an activity",
            Regex("""\bstartActivit(?:y|ies)(?:ForResult)?\b"""),
            setOf("data/platform/ActionExecutor.kt", "data/platform/AppSettingsOpener.kt"),
        ),
        Rule(
            "builds an Intent",
            Regex("""(?<![A-Za-z])Intent\("""),
            setOf("data/platform/OpenIntents.kt", "data/platform/AppSettingsOpener.kt"),
        ),
        Rule(
            "builds an Intent by a factory",
            Regex(
                """(?<![A-Za-z])Intent\.(?:createChooser|makeMainActivity|makeRestartActivityTask|getIntent)""",
            ),
            emptySet(),
        ),
        Rule("parses an Intent URI", Regex("""\bparseUri\b"""), emptySet()),
        Rule("uses a PendingIntent", Regex("""\bPendingIntent\b"""), emptySet()),
        Rule("grants a URI permission", Regex("""\bgrantUriPermission\b|FLAG_GRANT_"""), emptySet()),
        Rule(
            "launches an activity result contract",
            Regex("""\b(?:rememberLauncherForActivityResult|registerForActivityResult)\b"""),
            // Camera permission and the Photo Picker: no data from a scan, the user picks an image.
            setOf("ui/screens/scan/ScanRoute.kt"),
        ),
        Rule("opens a WebView", Regex("""\bWebView\b"""), emptySet()),
    )

    private fun mainSources(): Map<String, String> {
        val root = File("src/main/java")
        check(root.isDirectory) { "run from the app module: ${File(".").absolutePath}" }
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.associate { file ->
            file.relativeTo(File(root, "com/lookfirst")).invariantSeparatorsPath to withoutComments(file.readText())
        }
    }

    private fun withoutComments(text: String): String =
        text.replace(Regex("""(?s)/\*.*?\*/"""), "").replace(Regex("""(?m)^\s*//.*$|\s//\s.*$"""), "")

    @Test
    fun onlyTheAllowListedFilesLaunchAnything() {
        val sources = mainSources()
        assertTrue("sources not found", sources.size > 50)
        for (rule in rules) {
            val offenders = sources.filter { (path, text) ->
                rule.pattern.containsMatchIn(text) && path !in rule.allowed
            }
            assertEquals("${rule.name} outside the allow-list: ${offenders.keys}", emptySet<String>(), offenders.keys)
        }
    }

    @Test
    fun theAllowListHasNoStaleEntry() {
        val sources = mainSources()
        for (rule in rules) {
            for (path in rule.allowed) {
                val text = sources[path]
                assertTrue("${rule.name}: $path does not exist", text != null)
                assertTrue("${rule.name}: $path no longer matches, drop it", rule.pattern.containsMatchIn(text!!))
            }
        }
    }

    @Test
    fun onlyActionExecutorStartsActivitiesForScannedContent() {
        val starters = mainSources().filterValues { Regex("""\bstartActivit""").containsMatchIn(it) }.keys
        assertEquals(
            setOf("data/platform/ActionExecutor.kt", "data/platform/AppSettingsOpener.kt"),
            starters,
        )
    }

    @Test
    fun theDomainAndUiLayersNeverImportAndroidIntents() {
        val offenders = mainSources().filter { (path, text) ->
            (path.startsWith("domain/") || path.startsWith("ui/")) &&
                Regex("""import android\.content\.Intent\b""").containsMatchIn(text)
        }
        assertEquals(emptySet<String>(), offenders.keys)
    }
}
