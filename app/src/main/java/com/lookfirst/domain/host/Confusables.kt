package com.lookfirst.domain.host

import java.text.Normalizer
import java.util.Locale

private const val HEX_RADIX = 16
private const val MAX_SKELETON_INPUT = 512

/** Scripts (Unicode Script property) of a label, from the embedded Scripts.txt. */
internal object ScriptData {
    private const val COMMON = "Common"
    private const val INHERITED = "Inherited"

    private val table: RangeTable<String>? by lazy {
        HostResources.lines("scripts.txt")?.let { lines -> RangeTable.parse(lines) { it.ifEmpty { null } } }
    }

    val isAvailable: Boolean get() = table != null

    /**
     * The scripts used by [label], sorted, without Common and Inherited (digits, hyphen, combining marks).
     * Null when the embedded data is missing.
     */
    fun scriptsOf(label: String): List<String>? {
        val scripts = table ?: return null
        val found = sortedSetOf<String>()
        var i = 0
        while (i < label.length) {
            val cp = label.codePointAt(i)
            i += Character.charCount(cp)
            val script = scripts[cp] ?: "Unknown"
            if (script != COMMON && script != INHERITED) found += script
        }
        return found.toList()
    }
}

/**
 * UTS #39 skeleton with the embedded confusables.txt (MA table, IDNA-valid sources only): two strings that look
 * alike get the same skeleton. Used to compare a host label with a brand name, not to prove anything on its own.
 */
internal object Confusables {
    private val table: Map<Int, IntArray>? by lazy {
        HostResources.lines("confusables.txt")?.let { parse(it) }
    }

    val isAvailable: Boolean get() = table != null

    /** Lowercase; null when the data is missing or [text] is unreasonably long. */
    fun skeleton(text: String): String? {
        val map = table
        if (map == null || text.length > MAX_SKELETON_INPUT) return null
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        var i = 0
        while (i < decomposed.length) {
            val cp = decomposed.codePointAt(i)
            i += Character.charCount(cp)
            val target = map[cp]
            if (target == null) out.appendCodePoint(cp) else target.forEach { out.appendCodePoint(it) }
        }
        return Normalizer.normalize(out, Normalizer.Form.NFD).lowercase(Locale.ROOT)
    }

    internal fun parse(lines: List<String>): Map<Int, IntArray> {
        val map = HashMap<Int, IntArray>()
        for (line in lines) {
            if (line.isEmpty() || line[0] == '#') continue
            val cps = line.split(' ').mapNotNull { it.toIntOrNull(HEX_RADIX) }
            if (cps.size >= 2) map[cps[0]] = cps.drop(1).toIntArray()
        }
        return map
    }
}
