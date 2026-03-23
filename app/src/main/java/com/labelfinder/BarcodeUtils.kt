package com.labelfinder

object BarcodeUtils {

    /**
     * Legacy matching with character-set stripping. Kept for backward compatibility with tests.
     */
    fun barcodeMatches(scannedValue: String, searchTerm: String, stripChars: String): Boolean {
        if (stripChars.isEmpty()) return scannedValue.equals(searchTerm, ignoreCase = true)
        val stripSet = stripChars.toSet()
        val shouldStripPrefix = searchTerm.firstOrNull()?.let { it !in stripSet } ?: true
        val shouldStripSuffix = searchTerm.lastOrNull()?.let { it !in stripSet } ?: true
        var normalized = scannedValue
        if (shouldStripPrefix) normalized = normalized.trimStart { it in stripSet }
        if (shouldStripSuffix) normalized = normalized.trimEnd { it in stripSet }
        return normalized.equals(searchTerm, ignoreCase = true)
    }

    /**
     * Match with full-string prefix/suffix stripping (multi-pass).
     * Strips matching prefixes and suffixes repeatedly until stable,
     * then compares the result to the search term (case-insensitive).
     */
    fun barcodeMatches(
        scannedValue: String,
        searchTerm: String,
        prefixes: List<String>,
        suffixes: List<String>,
        partialMatch: Boolean = false
    ): Boolean {
        val normalized = stripPrefixesAndSuffixes(scannedValue, prefixes, suffixes)
        return if (partialMatch) {
            normalized.contains(searchTerm, ignoreCase = true)
        } else {
            normalized.equals(searchTerm, ignoreCase = true)
        }
    }

    /**
     * Multi-pass prefix/suffix stripping. Loops until no more matches.
     * Case-insensitive matching for prefixes and suffixes.
     */
    fun stripPrefixesAndSuffixes(
        value: String,
        prefixes: List<String>,
        suffixes: List<String>
    ): String {
        var result = value
        var changed = true
        while (changed) {
            changed = false
            for (prefix in prefixes) {
                if (prefix.isNotEmpty() && result.startsWith(prefix, ignoreCase = true)) {
                    result = result.substring(prefix.length)
                    changed = true
                    break // restart prefix loop after a strip
                }
            }
            for (suffix in suffixes) {
                if (suffix.isNotEmpty() && result.endsWith(suffix, ignoreCase = true)) {
                    result = result.substring(0, result.length - suffix.length)
                    changed = true
                    break // restart suffix loop after a strip
                }
            }
        }
        return result
    }

    /**
     * Parse a pipe-delimited string into a list of entries.
     * Used for storing prefix/suffix lists in the database.
     */
    fun parseList(delimited: String): List<String> {
        if (delimited.isBlank()) return emptyList()
        return delimited.split("|").filter { it.isNotEmpty() }
    }

    /**
     * Serialize a list of entries to a pipe-delimited string.
     */
    fun serializeList(entries: List<String>): String = entries.joinToString("|")

    /**
     * Add an entry to a list with case-insensitive deduplication.
     * Returns the updated list, or the original if it was a duplicate.
     */
    fun addUnique(list: List<String>, entry: String): List<String> {
        val trimmed = entry.trim()
        if (trimmed.isEmpty()) return list
        if (list.any { it.equals(trimmed, ignoreCase = true) }) return list
        return list + trimmed
    }

    fun volumeIndex(alertVolume: Int): Int = when (alertVolume) {
        0 -> 0
        in 1..33 -> 1
        in 34..66 -> 2
        else -> 3
    }

    fun <T> pruneHistory(
        history: MutableList<T>,
        maxItems: Int,
        maxAgeMs: Long,
        currentTimeMs: Long,
        timestampOf: (T) -> Long
    ) {
        val cutoff = currentTimeMs - maxAgeMs
        history.removeAll { timestampOf(it) < cutoff }
        while (history.size > maxItems) history.removeLast()
    }
}
