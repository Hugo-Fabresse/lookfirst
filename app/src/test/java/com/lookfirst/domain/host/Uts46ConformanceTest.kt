package com.lookfirst.domain.host

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the official IdnaTestV2.txt (Unicode UTS #46 conformance data, resource copy) against [Uts46]. */
class Uts46ConformanceTest {
    private val strict = Uts46Options(useStd3AsciiRules = true, checkHyphens = true, verifyDnsLength = true)

    private fun unescape(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("\\u", i) -> {
                    out.appendCodePoint(text.substring(i + 2, i + 6).toInt(16))
                    i += 6
                }
                text.startsWith("\\x{", i) -> {
                    val end = text.indexOf('}', i)
                    out.appendCodePoint(text.substring(i + 3, end).toInt(16))
                    i = end + 1
                }
                else -> out.append(text[i++])
            }
        }
        return out.toString()
    }

    private fun value(column: String, fallback: String): String = when {
        column == "\"\"" -> ""
        column.isEmpty() -> fallback
        else -> unescape(column)
    }

    private fun hasError(status: String) = status.trim().removePrefix("[").removeSuffix("]").isNotBlank()

    private class Case(val line: String, val source: String, val unicode: String, val ascii: String, val error: Boolean)

    private fun parseCase(line: String): Case? {
        val cols = line.substringBefore('#').split(';').map { it.trim() }
        if (cols.size < 5) return null
        val source = value(cols[0], "")
        val unicode = value(cols[1], source)
        val asciiStatus = if (cols[4].isEmpty()) cols[2] else cols[4]
        return Case(line, source, unicode, value(cols[3], unicode), hasError(asciiStatus))
    }

    @Test
    fun officialIdnaTestV2() {
        val tables = UnicodeTables.instance
        assertNotNull(tables)
        val lines = javaClass.getResourceAsStream("/lookfirst/host/IdnaTestV2.txt")!!.bufferedReader().readLines()
        val cases = lines.mapNotNull { parseCase(it) }
        val failures = cases.mapNotNull { case ->
            val result = Uts46.process(case.source, strict, tables!!)
            val bad = when (result) {
                is Uts46Result.Failure -> !case.error
                is Uts46Result.Ok -> case.error || result.ascii != case.ascii || result.unicode != case.unicode
            }
            if (bad) "${case.line}  -> $result" else null
        }
        assertTrue(
            "${cases.size} cases, ${failures.size} failures:\n" + failures.take(25).joinToString("\n"),
            failures.isEmpty(),
        )
        assertTrue(cases.size > 5000)
    }
}
