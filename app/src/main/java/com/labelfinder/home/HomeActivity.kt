package com.labelfinder.home

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.labelfinder.R
import com.labelfinder.data.AppDatabase
import com.labelfinder.data.SearchRepository
import com.labelfinder.databinding.ActivityHomeBinding
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding

    private val viewModel: HomeViewModel by viewModels {
        HomeViewModel.Factory(SearchRepository(AppDatabase.getInstance(this)))
    }

    private val scanCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val barcode = result.data?.getStringExtra(ScanCaptureActivity.RESULT_BARCODE)
            if (!barcode.isNullOrBlank()) {
                binding.barcodeInput.setText(barcode)
                binding.barcodeInput.setSelection(barcode.length)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupInput()
        setupButtons()
        observeState()
    }

    private fun setupInput() {
        binding.barcodeInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addCurrentInput()
                true
            } else false
        }
    }

    private fun setupButtons() {
        binding.addButton.setOnClickListener { addCurrentInput() }

        binding.findButton.setOnClickListener {
            if (viewModel.canFind()) {
                viewModel.recordSearch()
                // TODO: Launch FinderActivity with viewModel.searchList.value
            }
        }

        binding.scanButton.setOnClickListener {
            scanCaptureLauncher.launch(Intent(this, ScanCaptureActivity::class.java))
        }

        binding.settingsButton.setOnClickListener {
            // TODO: Open settings (Phase 4, task #19)
        }
    }

    private fun addCurrentInput() {
        val text = binding.barcodeInput.text?.toString() ?: return
        if (viewModel.addToList(text)) {
            binding.barcodeInput.text?.clear()
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.searchList.collect { updateChips(it) } }
                launch { viewModel.snackbar.collect { msg ->
                    if (msg != null) {
                        Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
                        viewModel.snackbarShown()
                    }
                } }
                launch { viewModel.recentHistory.collect { history ->
                    val hasHistory = history.isNotEmpty()
                    binding.recentHeader.visibility = if (hasHistory) android.view.View.VISIBLE else android.view.View.GONE
                    binding.recentList.visibility = if (hasHistory) android.view.View.VISIBLE else android.view.View.GONE
                    // TODO: Wire up RecyclerView adapter (Phase 2, task #16)
                } }
            }
        }
    }

    private fun updateChips(barcodes: List<String>) {
        binding.chipGroup.removeAllViews()
        for (barcode in barcodes) {
            val chip = Chip(this).apply {
                text = barcode
                typeface = android.graphics.Typeface.MONOSPACE
                isCloseIconVisible = true
                setOnCloseIconClickListener { viewModel.removeFromList(barcode) }
            }
            binding.chipGroup.addView(chip)
        }
        binding.findButton.isEnabled = viewModel.canFind()
        binding.findButton.text = viewModel.findButtonLabel()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.barcodeInput.windowToken, 0)
    }
}
