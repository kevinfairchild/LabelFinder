package com.labelfinder.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchHistoryDao {

    @Insert
    suspend fun insert(entry: SearchHistory)

    @Query("SELECT * FROM search_history ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int = 20): Flow<List<SearchHistory>>

    @Query("SELECT * FROM search_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentOnce(limit: Int = 20): List<SearchHistory>

    @Query("UPDATE search_history SET found = :found WHERE id = :id")
    suspend fun updateFound(id: Long, found: Boolean)

    @Query("DELETE FROM search_history WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM search_history WHERE id NOT IN (SELECT id FROM search_history ORDER BY timestamp DESC LIMIT :maxItems)")
    suspend fun trimToMax(maxItems: Int)

    @Query("DELETE FROM search_history")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM search_history")
    suspend fun count(): Int
}
