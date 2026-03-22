package com.labelfinder.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.labelfinder.data.SearchHistory
import com.labelfinder.data.SearchRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(private val repository: SearchRepository) : ViewModel() {

    // Current search list (barcodes to find)
    private val _searchList = MutableStateFlow<List<String>>(emptyList())
    val searchList: StateFlow<List<String>> = _searchList.asStateFlow()

    // Snackbar message (e.g., duplicate warning)
    private val _snackbar = MutableStateFlow<String?>(null)
    val snackbar: StateFlow<String?> = _snackbar.asStateFlow()

    // Recent search history
    val recentHistory: StateFlow<List<SearchHistory>> = repository.recentHistory(20)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addToList(barcode: String): Boolean {
        val trimmed = barcode.trim()
        if (trimmed.isEmpty()) return false
        val current = _searchList.value
        if (current.any { it.equals(trimmed, ignoreCase = true) }) {
            _snackbar.value = "\"$trimmed\" is already in the list"
            return false
        }
        _searchList.value = current + trimmed
        return true
    }

    fun removeFromList(barcode: String) {
        _searchList.value = _searchList.value.filter { !it.equals(barcode, ignoreCase = true) }
    }

    fun clearList() {
        _searchList.value = emptyList()
    }

    fun snackbarShown() {
        _snackbar.value = null
    }

    fun findButtonLabel(): String {
        val count = _searchList.value.size
        return when (count) {
            0 -> "Find Barcode"
            1 -> "Find Barcode"
            else -> "Find $count Barcodes"
        }
    }

    fun canFind(): Boolean = _searchList.value.isNotEmpty()

    fun addHistoryToList(barcode: String) {
        addToList(barcode)
    }

    fun recordSearch() {
        viewModelScope.launch {
            for (barcode in _searchList.value) {
                repository.addToHistory(barcode)
            }
        }
    }

    class Factory(private val repository: SearchRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(repository) as T
        }
    }
}
