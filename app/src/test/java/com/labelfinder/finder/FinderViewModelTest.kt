package com.labelfinder.finder

import org.junit.Assert.*
import org.junit.Test

class FinderViewModelTest {

    @Test
    fun `single search initializes correctly`() {
        val vm = FinderViewModel(listOf("ABC123"), "*+")
        val state = vm.state.value
        assertFalse(state.isMultiSearch)
        assertEquals(1, state.targets.size)
        assertEquals("ABC123", state.targets[0].barcode)
        assertEquals(TargetStatus.SEARCHING, state.targets[0].status)
    }

    @Test
    fun `multi search initializes correctly`() {
        val vm = FinderViewModel(listOf("A", "B", "C"), "*+")
        val state = vm.state.value
        assertTrue(state.isMultiSearch)
        assertEquals(3, state.targets.size)
    }

    @Test
    fun `onBarcodesDetected spots matching target`() {
        val vm = FinderViewModel(listOf("ABC123"), "*+")
        val newlySpotted = vm.onBarcodesDetected(listOf("ABC123"))
        assertEquals(listOf(0), newlySpotted)
        assertEquals(TargetStatus.SPOTTED, vm.state.value.targets[0].status)
    }

    @Test
    fun `onBarcodesDetected returns empty when no match`() {
        val vm = FinderViewModel(listOf("ABC123"), "*+")
        val newlySpotted = vm.onBarcodesDetected(listOf("XYZ"))
        assertTrue(newlySpotted.isEmpty())
        assertEquals(TargetStatus.SEARCHING, vm.state.value.targets[0].status)
    }

    @Test
    fun `onBarcodesDetected does not re-alert same target`() {
        val vm = FinderViewModel(listOf("ABC123"), "*+")
        vm.onBarcodesDetected(listOf("ABC123")) // first time — alerts
        val second = vm.onBarcodesDetected(listOf("ABC123")) // second time — no alert
        assertTrue(second.isEmpty())
    }

    @Test
    fun `spotted target reverts to searching when lost`() {
        val vm = FinderViewModel(listOf("ABC123"), "*+")
        vm.onBarcodesDetected(listOf("ABC123"))
        assertEquals(TargetStatus.SPOTTED, vm.state.value.targets[0].status)
        vm.onBarcodesDetected(emptyList()) // lost
        assertEquals(TargetStatus.SEARCHING, vm.state.value.targets[0].status)
    }

    @Test
    fun `markFound changes status`() {
        val vm = FinderViewModel(listOf("ABC"), "*+")
        vm.onBarcodesDetected(listOf("ABC"))
        vm.markFound(0)
        assertEquals(TargetStatus.FOUND, vm.state.value.targets[0].status)
    }

    @Test
    fun `found target is not affected by detection`() {
        val vm = FinderViewModel(listOf("ABC"), "*+")
        vm.markFound(0)
        vm.onBarcodesDetected(listOf("ABC"))
        assertEquals(TargetStatus.FOUND, vm.state.value.targets[0].status)
    }

    @Test
    fun `unmarkFound reverts to searching and allows re-alert`() {
        val vm = FinderViewModel(listOf("ABC"), "*+")
        vm.onBarcodesDetected(listOf("ABC"))
        vm.markFound(0)
        vm.unmarkFound(0)
        assertEquals(TargetStatus.SEARCHING, vm.state.value.targets[0].status)
        val alerts = vm.onBarcodesDetected(listOf("ABC"))
        assertEquals(listOf(0), alerts) // re-alerted
    }

    @Test
    fun `allFound is true when all marked`() {
        val vm = FinderViewModel(listOf("A", "B"), "*+")
        assertFalse(vm.state.value.allFound)
        vm.markFound(0)
        assertFalse(vm.state.value.allFound)
        vm.markFound(1)
        assertTrue(vm.state.value.allFound)
    }

    @Test
    fun `foundCount tracks correctly`() {
        val vm = FinderViewModel(listOf("A", "B", "C"), "*+")
        assertEquals(0, vm.state.value.foundCount)
        vm.markFound(1)
        assertEquals(1, vm.state.value.foundCount)
        vm.markFound(2)
        assertEquals(2, vm.state.value.foundCount)
    }

    @Test
    fun `matchingTargetIndex returns correct index`() {
        val vm = FinderViewModel(listOf("A", "B", "C"), "*+")
        assertEquals(1, vm.matchingTargetIndex("B"))
        assertEquals(-1, vm.matchingTargetIndex("Z"))
    }

    @Test
    fun `matchingTargetIndex skips found targets`() {
        val vm = FinderViewModel(listOf("A", "B"), "*+")
        vm.markFound(0)
        assertEquals(-1, vm.matchingTargetIndex("A"))
        assertEquals(1, vm.matchingTargetIndex("B"))
    }

    @Test
    fun `strip chars are applied in matching`() {
        val vm = FinderViewModel(listOf("ABC123"), "*+")
        val spotted = vm.onBarcodesDetected(listOf("*ABC123+"))
        assertEquals(listOf(0), spotted)
    }

    @Test
    fun `multi search alerts only new targets`() {
        val vm = FinderViewModel(listOf("A", "B", "C"), "*+")
        val first = vm.onBarcodesDetected(listOf("A", "B"))
        assertEquals(listOf(0, 1), first)
        val second = vm.onBarcodesDetected(listOf("A", "C"))
        assertEquals(listOf(2), second) // only C is new
    }
}
