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
}
