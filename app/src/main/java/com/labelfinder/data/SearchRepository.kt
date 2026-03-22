package com.labelfinder.data

import kotlinx.coroutines.flow.Flow

class SearchRepository(private val db: AppDatabase) {

    private val historyDao = db.searchHistoryDao()
    private val settingsDao = db.appSettingsDao()

    companion object {
        const val HISTORY_MAX_ITEMS = 200
        const val HISTORY_MAX_AGE_MS = 90L * 24 * 60 * 60 * 1000 // 90 days
    }

    // ---- History ----

    fun recentHistory(limit: Int = 20): Flow<List<SearchHistory>> = historyDao.getRecent(limit)

    suspend fun addToHistory(barcode: String) {
        historyDao.upsert(SearchHistory(barcode = barcode, timestamp = System.currentTimeMillis()))
        pruneHistory()
    }

    suspend fun markFound(id: Long, found: Boolean) {
        historyDao.updateFound(id, found)
    }

    suspend fun clearHistory() {
        historyDao.clearAll()
    }

    private suspend fun pruneHistory() {
        val cutoff = System.currentTimeMillis() - HISTORY_MAX_AGE_MS
        historyDao.deleteOlderThan(cutoff)
        historyDao.trimToMax(HISTORY_MAX_ITEMS)
    }

    // ---- Settings ----

    fun observeSettings(): Flow<AppSettings?> = settingsDao.observe()

    suspend fun getSettings(): AppSettings = settingsDao.get() ?: AppSettings()

    suspend fun saveSettings(settings: AppSettings) {
        settingsDao.save(settings)
    }
}
