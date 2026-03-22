package com.labelfinder.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchHistoryDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: SearchHistoryDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.searchHistoryDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun insertAndRetrieve() = runTest {
        dao.insert(SearchHistory(barcode = "ABC123", timestamp = 1000L))
        val results = dao.getRecentOnce(10)
        assertEquals(1, results.size)
        assertEquals("ABC123", results[0].barcode)
    }

    @Test
    fun recentOrderedByTimestampDesc() = runTest {
        dao.insert(SearchHistory(barcode = "OLD", timestamp = 1000L))
        dao.insert(SearchHistory(barcode = "NEW", timestamp = 2000L))
        val results = dao.getRecentOnce(10)
        assertEquals("NEW", results[0].barcode)
        assertEquals("OLD", results[1].barcode)
    }

    @Test
    fun limitReturnsOnlyRequestedCount() = runTest {
        for (i in 1..5) dao.insert(SearchHistory(barcode = "B$i", timestamp = i.toLong()))
        val results = dao.getRecentOnce(3)
        assertEquals(3, results.size)
    }

    @Test
    fun updateFound() = runTest {
        dao.insert(SearchHistory(barcode = "ABC", timestamp = 1000L))
        val entry = dao.getRecentOnce(1).first()
        assertFalse(entry.found)
        dao.updateFound(entry.id, true)
        val updated = dao.getRecentOnce(1).first()
        assertTrue(updated.found)
    }

    @Test
    fun deleteOlderThan() = runTest {
        dao.insert(SearchHistory(barcode = "OLD", timestamp = 100L))
        dao.insert(SearchHistory(barcode = "NEW", timestamp = 2000L))
        dao.deleteOlderThan(500L)
        val results = dao.getRecentOnce(10)
        assertEquals(1, results.size)
        assertEquals("NEW", results[0].barcode)
    }

    @Test
    fun trimToMax() = runTest {
        for (i in 1..10) dao.insert(SearchHistory(barcode = "B$i", timestamp = i.toLong()))
        assertEquals(10, dao.count())
        dao.trimToMax(5)
        assertEquals(5, dao.count())
        // Should keep the 5 most recent
        val results = dao.getRecentOnce(10)
        assertEquals("B10", results[0].barcode)
        assertEquals("B6", results[4].barcode)
    }

    @Test
    fun clearAll() = runTest {
        for (i in 1..3) dao.insert(SearchHistory(barcode = "B$i", timestamp = i.toLong()))
        assertEquals(3, dao.count())
        dao.clearAll()
        assertEquals(0, dao.count())
    }
}
