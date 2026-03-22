package com.labelfinder.home

import com.labelfinder.data.AppDatabase
import com.labelfinder.data.SearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        // Use a mock repository since we're testing ViewModel logic, not DB
        val mockDb = Mockito.mock(AppDatabase::class.java)
        val repo = Mockito.mock(SearchRepository::class.java)
        Mockito.`when`(repo.recentHistory(20)).thenReturn(kotlinx.coroutines.flow.flowOf(emptyList()))
        viewModel = HomeViewModel(repo)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `addToList adds barcode`() {
        assertTrue(viewModel.addToList("ABC123"))
        assertEquals(listOf("ABC123"), viewModel.searchList.value)
    }

    @Test
    fun `addToList trims whitespace`() {
        assertTrue(viewModel.addToList("  ABC123  "))
        assertEquals(listOf("ABC123"), viewModel.searchList.value)
    }

    @Test
    fun `addToList rejects empty string`() {
        assertFalse(viewModel.addToList(""))
        assertFalse(viewModel.addToList("   "))
        assertTrue(viewModel.searchList.value.isEmpty())
    }

    @Test
    fun `addToList rejects duplicate case insensitive`() {
        assertTrue(viewModel.addToList("ABC123"))
        assertFalse(viewModel.addToList("abc123"))
        assertEquals(1, viewModel.searchList.value.size)
    }

    @Test
    fun `addToList sets snackbar on duplicate`() {
        viewModel.addToList("ABC123")
        viewModel.addToList("abc123")
        assertNotNull(viewModel.snackbar.value)
        assertTrue(viewModel.snackbar.value!!.contains("abc123"))
    }

    @Test
    fun `snackbarShown clears message`() {
        viewModel.addToList("A")
        viewModel.addToList("A")
        assertNotNull(viewModel.snackbar.value)
        viewModel.snackbarShown()
        assertNull(viewModel.snackbar.value)
    }

    @Test
    fun `removeFromList removes barcode`() {
        viewModel.addToList("A")
        viewModel.addToList("B")
        viewModel.removeFromList("A")
        assertEquals(listOf("B"), viewModel.searchList.value)
    }

    @Test
    fun `removeFromList is case insensitive`() {
        viewModel.addToList("ABC")
        viewModel.removeFromList("abc")
        assertTrue(viewModel.searchList.value.isEmpty())
    }

    @Test
    fun `clearList removes all`() {
        viewModel.addToList("A")
        viewModel.addToList("B")
        viewModel.clearList()
        assertTrue(viewModel.searchList.value.isEmpty())
    }

    @Test
    fun `canFind is false when list empty`() {
        assertFalse(viewModel.canFind())
    }

    @Test
    fun `canFind is true when list has items`() {
        viewModel.addToList("A")
        assertTrue(viewModel.canFind())
    }

    @Test
    fun `findButtonLabel for zero items`() {
        assertEquals("Find Barcode", viewModel.findButtonLabel())
    }

    @Test
    fun `findButtonLabel for one item`() {
        viewModel.addToList("A")
        assertEquals("Find Barcode", viewModel.findButtonLabel())
    }

    @Test
    fun `findButtonLabel for multiple items`() {
        viewModel.addToList("A")
        viewModel.addToList("B")
        viewModel.addToList("C")
        assertEquals("Find 3 Barcodes", viewModel.findButtonLabel())
    }
}
