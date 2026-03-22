package com.labelfinder

object BarcodeUtils {

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
