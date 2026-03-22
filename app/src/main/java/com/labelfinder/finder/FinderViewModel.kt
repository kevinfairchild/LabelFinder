package com.labelfinder.finder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.labelfinder.BarcodeUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SearchTarget(
    val barcode: String,
    val colorIndex: Int,
    val status: TargetStatus = TargetStatus.SEARCHING
)

enum class TargetStatus { SEARCHING, SPOTTED, FOUND }

data class FinderUiState(
    val targets: List<SearchTarget> = emptyList(),
    val isMultiSearch: Boolean = false,
    val stripChars: String = "*+"
) {
    val allFound: Boolean get() = targets.all { it.status == TargetStatus.FOUND }
    val foundCount: Int get() = targets.count { it.status == TargetStatus.FOUND }
    val totalCount: Int get() = targets.size
}

class FinderViewModel(
    barcodes: List<String>,
    stripChars: String
) : ViewModel() {

    private val _state = MutableStateFlow(
        FinderUiState(
            targets = barcodes.mapIndexed { i, b -> SearchTarget(b, i) },
            isMultiSearch = barcodes.size > 1,
            stripChars = stripChars
        )
    )
    val state: StateFlow<FinderUiState> = _state.asStateFlow()

    // Track which barcodes have already triggered an alert (by target index)
    private val alertedIndices = mutableSetOf<Int>()

    // Debounce: count frames since each target was last seen (prevents flickering)
    private val missedFrames = mutableMapOf<Int, Int>()
    private val missedFrameThreshold = 15 // ~0.5s at 30fps before reverting to SEARCHING

    /**
     * Called each frame with detected barcode values.
     * Returns indices of newly spotted targets (for triggering alerts).
     */
    fun onBarcodesDetected(scannedValues: List<String>): List<Int> {
        val current = _state.value
        val newlySpotted = mutableListOf<Int>()

        val updatedTargets = current.targets.mapIndexed { i, target ->
            if (target.status == TargetStatus.FOUND) return@mapIndexed target

            val isMatch = scannedValues.any { scanned ->
                BarcodeUtils.barcodeMatches(scanned, target.barcode, current.stripChars)
            }

            if (isMatch) {
                missedFrames.remove(i)
                if (target.status == TargetStatus.SEARCHING) {
                    if (i !in alertedIndices) {
                        alertedIndices.add(i)
                        newlySpotted.add(i)
                    }
                    target.copy(status = TargetStatus.SPOTTED)
                } else {
                    target // already SPOTTED, keep it
                }
            } else if (target.status == TargetStatus.SPOTTED) {
                val missed = (missedFrames[i] ?: 0) + 1
                missedFrames[i] = missed
                if (missed >= missedFrameThreshold) {
                    missedFrames.remove(i)
                    target.copy(status = TargetStatus.SEARCHING)
                } else {
                    target // stay SPOTTED during debounce window
                }
            } else {
                target
            }
        }

        _state.value = current.copy(targets = updatedTargets)
        return newlySpotted
    }

    fun markFound(index: Int) {
        val current = _state.value
        if (index !in current.targets.indices) return
        val updated = current.targets.toMutableList()
        updated[index] = updated[index].copy(status = TargetStatus.FOUND)
        _state.value = current.copy(targets = updated)
    }

    fun unmarkFound(index: Int) {
        val current = _state.value
        if (index !in current.targets.indices) return
        val updated = current.targets.toMutableList()
        updated[index] = updated[index].copy(status = TargetStatus.SEARCHING)
        // Allow re-alerting
        alertedIndices.remove(index)
        _state.value = current.copy(targets = updated)
    }

    /**
     * Check if a scanned barcode matches any active (non-found) target.
     * Returns the target index or -1.
     */
    fun matchingTargetIndex(scannedValue: String): Int {
        val current = _state.value
        return current.targets.indexOfFirst { target ->
            target.status != TargetStatus.FOUND &&
                BarcodeUtils.barcodeMatches(scannedValue, target.barcode, current.stripChars)
        }
    }

    class Factory(
        private val barcodes: List<String>,
        private val stripChars: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FinderViewModel(barcodes, stripChars) as T
        }
    }
}
