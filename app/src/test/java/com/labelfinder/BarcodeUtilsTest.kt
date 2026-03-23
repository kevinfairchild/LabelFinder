package com.labelfinder

import org.junit.Assert.*
import org.junit.Test

class BarcodeUtilsTest {

    // ---- barcodeMatches ----

    @Test
    fun `exact match is case insensitive`() {
        assertTrue(BarcodeUtils.barcodeMatches("ABC123", "abc123", ""))
        assertTrue(BarcodeUtils.barcodeMatches("abc123", "ABC123", ""))
    }

    @Test
    fun `no match returns false`() {
        assertFalse(BarcodeUtils.barcodeMatches("ABC123", "XYZ789", ""))
    }

    @Test
    fun `strips prefix characters from scanned value`() {
        assertTrue(BarcodeUtils.barcodeMatches("*ABC123", "ABC123", "*+"))
    }

    @Test
    fun `strips suffix characters from scanned value`() {
        assertTrue(BarcodeUtils.barcodeMatches("ABC123+", "ABC123", "*+"))
    }

    @Test
    fun `strips both prefix and suffix`() {
        assertTrue(BarcodeUtils.barcodeMatches("**ABC123++", "ABC123", "*+"))
    }

    @Test
    fun `does not strip if search term starts with strip char`() {
        // Search is "*ABC" — starts with *, so don't strip prefix
        assertTrue(BarcodeUtils.barcodeMatches("*ABC", "*ABC", "*+"))
        assertFalse(BarcodeUtils.barcodeMatches("ABC", "*ABC", "*+"))
    }

    @Test
    fun `does not strip if search term ends with strip char`() {
        // Search is "ABC+" — ends with +, so don't strip suffix
        assertTrue(BarcodeUtils.barcodeMatches("ABC+", "ABC+", "*+"))
        assertFalse(BarcodeUtils.barcodeMatches("ABC", "ABC+", "*+"))
    }

    @Test
    fun `empty strip chars means exact match only`() {
        assertFalse(BarcodeUtils.barcodeMatches("*ABC123", "ABC123", ""))
        assertTrue(BarcodeUtils.barcodeMatches("ABC123", "ABC123", ""))
    }

    @Test
    fun `empty strings match`() {
        assertTrue(BarcodeUtils.barcodeMatches("", "", ""))
        assertTrue(BarcodeUtils.barcodeMatches("", "", "*+"))
    }

    // ---- volumeIndex ----

    @Test
    fun `volume 0 maps to index 0 (Off)`() {
        assertEquals(0, BarcodeUtils.volumeIndex(0))
    }

    @Test
    fun `volume 33 maps to index 1 (Low)`() {
        assertEquals(1, BarcodeUtils.volumeIndex(33))
    }

    @Test
    fun `volume 66 maps to index 2 (Medium)`() {
        assertEquals(2, BarcodeUtils.volumeIndex(66))
    }

    @Test
    fun `volume 100 maps to index 3 (High)`() {
        assertEquals(3, BarcodeUtils.volumeIndex(100))
    }

    @Test
    fun `volume boundary at 34 maps to Medium`() {
        assertEquals(2, BarcodeUtils.volumeIndex(34))
    }

    @Test
    fun `volume 1 maps to Low`() {
        assertEquals(1, BarcodeUtils.volumeIndex(1))
    }

    // ---- pruneHistory ----

    data class Entry(val value: String, val timestamp: Long)

    @Test
    fun `prune removes entries older than max age`() {
        val now = 100_000L
        val history = mutableListOf(
            Entry("a", 90_000L),  // 10s old
            Entry("b", 50_000L),  // 50s old
            Entry("c", 10_000L)   // 90s old
        )
        BarcodeUtils.pruneHistory(history, 100, 30_000L, now) { it.timestamp }
        assertEquals(1, history.size)
        assertEquals("a", history[0].value)
    }

    @Test
    fun `prune caps at max items keeping earliest entries in list`() {
        val now = 100_000L
        val history = mutableListOf(
            Entry("a", 99_000L),
            Entry("b", 98_000L),
            Entry("c", 97_000L),
            Entry("d", 96_000L),
            Entry("e", 95_000L)
        )
        BarcodeUtils.pruneHistory(history, 3, 200_000L, now) { it.timestamp }
        assertEquals(3, history.size)
        assertEquals("a", history[0].value)
        assertEquals("b", history[1].value)
        assertEquals("c", history[2].value)
    }

    @Test
    fun `prune applies age first then count`() {
        val now = 100_000L
        val history = mutableListOf(
            Entry("a", 99_000L),  // 1s old — keep
            Entry("b", 98_000L),  // 2s old — keep
            Entry("c", 80_000L),  // 20s old — aged out
            Entry("d", 70_000L)   // 30s old — aged out
        )
        // Max age 10s, max items 1
        BarcodeUtils.pruneHistory(history, 1, 10_000L, now) { it.timestamp }
        assertEquals(1, history.size)
        assertEquals("a", history[0].value)
    }

    @Test
    fun `prune with empty list does nothing`() {
        val history = mutableListOf<Entry>()
        BarcodeUtils.pruneHistory(history, 10, 10_000L, 100_000L) { it.timestamp }
        assertTrue(history.isEmpty())
    }

    @Test
    fun `prune keeps all when under limits`() {
        val now = 100_000L
        val history = mutableListOf(
            Entry("a", 99_000L),
            Entry("b", 98_000L)
        )
        BarcodeUtils.pruneHistory(history, 10, 200_000L, now) { it.timestamp }
        assertEquals(2, history.size)
    }

    // ---- New prefix/suffix matching ----

    @Test
    fun `prefix strip removes matching prefix`() {
        assertTrue(BarcodeUtils.barcodeMatches("Order%1234", "1234", listOf("Order%"), emptyList()))
    }

    @Test
    fun `suffix strip removes matching suffix`() {
        assertTrue(BarcodeUtils.barcodeMatches("1234+", "1234", emptyList(), listOf("+")))
    }

    @Test
    fun `both prefix and suffix stripped`() {
        assertTrue(BarcodeUtils.barcodeMatches("*Order%1234+", "1234", listOf("*", "Order%"), listOf("+")))
    }

    @Test
    fun `multi-pass strips nested prefixes`() {
        assertTrue(BarcodeUtils.barcodeMatches("*Order%1234", "1234", listOf("Order%", "*"), emptyList()))
    }

    @Test
    fun `multi-pass strips nested suffixes`() {
        assertTrue(BarcodeUtils.barcodeMatches("1234+-END", "1234", emptyList(), listOf("-END", "+")))
    }

    @Test
    fun `multi-pass handles prefix then suffix then prefix`() {
        // Order%*1234+ → strip Order% → *1234+ → strip * → 1234+ → strip + → 1234
        assertTrue(BarcodeUtils.barcodeMatches("Order%*1234+", "1234", listOf("Order%", "*"), listOf("+")))
    }

    @Test
    fun `prefix strip is case insensitive`() {
        assertTrue(BarcodeUtils.barcodeMatches("order%1234", "1234", listOf("Order%"), emptyList()))
        assertTrue(BarcodeUtils.barcodeMatches("ORDER%1234", "1234", listOf("Order%"), emptyList()))
    }

    @Test
    fun `no match when no prefix or suffix applies`() {
        assertFalse(BarcodeUtils.barcodeMatches("XYZ1234", "1234", listOf("Order%"), emptyList()))
    }

    @Test
    fun `exact match when no prefixes or suffixes configured`() {
        assertTrue(BarcodeUtils.barcodeMatches("1234", "1234", emptyList(), emptyList()))
        assertFalse(BarcodeUtils.barcodeMatches("Order%1234", "1234", emptyList(), emptyList()))
    }

    @Test
    fun `empty scanned value does not crash`() {
        assertFalse(BarcodeUtils.barcodeMatches("", "1234", listOf("*"), listOf("+")))
    }

    @Test
    fun `prefix that equals entire scanned value results in empty`() {
        // "Order%" stripped → "" which doesn't match "1234"
        assertFalse(BarcodeUtils.barcodeMatches("Order%", "1234", listOf("Order%"), emptyList()))
    }

    // ---- Partial matching ----

    @Test
    fun `partial match finds substring`() {
        assertTrue(BarcodeUtils.barcodeMatches("Order%1234", "1234", emptyList(), emptyList(), partialMatch = true))
    }

    @Test
    fun `partial match is case insensitive`() {
        assertTrue(BarcodeUtils.barcodeMatches("ABC1234XYZ", "abc1234xyz", emptyList(), emptyList(), partialMatch = true))
        assertTrue(BarcodeUtils.barcodeMatches("ABC1234XYZ", "1234", emptyList(), emptyList(), partialMatch = true))
    }

    @Test
    fun `partial match works with prefix stripping`() {
        // Strip "Order%" first → "1234ABC", then partial match "1234"
        assertTrue(BarcodeUtils.barcodeMatches("Order%1234ABC", "1234", listOf("Order%"), emptyList(), partialMatch = true))
    }

    @Test
    fun `partial match rejects when substring not present`() {
        assertFalse(BarcodeUtils.barcodeMatches("ABCXYZ", "1234", emptyList(), emptyList(), partialMatch = true))
    }

    @Test
    fun `exact match rejects substring when partial disabled`() {
        assertFalse(BarcodeUtils.barcodeMatches("Order%1234", "1234", emptyList(), emptyList(), partialMatch = false))
    }

    // ---- addUnique ----

    @Test
    fun `addUnique adds new entry`() {
        val list = listOf("A", "B")
        val result = BarcodeUtils.addUnique(list, "C")
        assertEquals(listOf("A", "B", "C"), result)
    }

    @Test
    fun `addUnique rejects case-insensitive duplicate`() {
        val list = listOf("Order%")
        val result = BarcodeUtils.addUnique(list, "order%")
        assertEquals(listOf("Order%"), result) // unchanged
    }

    @Test
    fun `addUnique rejects exact duplicate`() {
        val list = listOf("*")
        assertEquals(list, BarcodeUtils.addUnique(list, "*"))
    }

    @Test
    fun `addUnique trims whitespace`() {
        val result = BarcodeUtils.addUnique(emptyList(), "  Order%  ")
        assertEquals(listOf("Order%"), result)
    }

    @Test
    fun `addUnique rejects empty string`() {
        val result = BarcodeUtils.addUnique(emptyList(), "  ")
        assertTrue(result.isEmpty())
    }

    // ---- parseList / serializeList ----

    @Test
    fun `parseList splits pipe-delimited`() {
        assertEquals(listOf("A", "B", "C"), BarcodeUtils.parseList("A|B|C"))
    }

    @Test
    fun `parseList returns empty for blank`() {
        assertTrue(BarcodeUtils.parseList("").isEmpty())
        assertTrue(BarcodeUtils.parseList("  ").isEmpty())
    }

    @Test
    fun `serializeList joins with pipe`() {
        assertEquals("A|B|C", BarcodeUtils.serializeList(listOf("A", "B", "C")))
    }

    @Test
    fun `round-trip parse and serialize`() {
        val original = listOf("Order%", "*", "SKU-")
        assertEquals(original, BarcodeUtils.parseList(BarcodeUtils.serializeList(original)))
    }

}
