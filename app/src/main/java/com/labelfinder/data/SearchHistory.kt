package com.labelfinder.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "search_history")
data class SearchHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val barcode: String,
    val timestamp: Long,
    val found: Boolean = false
)
