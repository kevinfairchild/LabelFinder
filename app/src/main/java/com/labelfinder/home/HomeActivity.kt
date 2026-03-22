package com.labelfinder.home

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.labelfinder.R
import com.labelfinder.data.AppDatabase
import com.labelfinder.data.SearchRepository
import com.labelfinder.finder.FinderActivity
import com.labelfinder.settings.SettingsActivity
import com.labelfinder.databinding.ActivityHomeBinding
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var recentAdapter: RecentSearchAdapter

    private val viewModel: HomeViewModel by viewModels {
        HomeViewModel.Factory(SearchRepository(AppDatabase.getInstance(this)))
    }

    private val scanCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val barcodes = result.data?.getStringArrayExtra(ScanCaptureActivity.RESULT_BARCODES)
            if (barcodes != null && barcodes.size > 1) {
                // Multiple selected — add all to list
                for (b in barcodes) viewModel.addToList(b)
            } else {
                // Single selected — populate text field
                val barcode = result.data?.getStringExtra(ScanCaptureActivity.RESULT_BARCODE)
                if (!barcode.isNullOrBlank()) {
                    binding.barcodeInput.setText(barcode)
                    binding.barcodeInput.setSelection(barcode.length)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupInput()
        setupButtons()
        setupRecentList()
        observeState()
    }

    private fun setupInput() {
        binding.addButton.isEnabled = false
        binding.barcodeInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                binding.addButton.isEnabled = !s.isNullOrBlank()
            }
        })
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
                lifecycleScope.launch {
                    val repo = SearchRepository(AppDatabase.getInstance(this@HomeActivity))
                    val settings = repo.getSettings()
                    val prefixes = com.labelfinder.BarcodeUtils.parseList(settings.prefixes)
                    val suffixes = com.labelfinder.BarcodeUtils.parseList(settings.suffixes)
                    val intent = Intent(this@HomeActivity, FinderActivity::class.java).apply {
                        putExtra(FinderActivity.EXTRA_BARCODES, viewModel.searchList.value.toTypedArray())
                        putExtra(FinderActivity.EXTRA_PREFIXES, prefixes.toTypedArray())
                        putExtra(FinderActivity.EXTRA_SUFFIXES, suffixes.toTypedArray())
                    }
                    startActivity(intent)
                }
            }
        }

        binding.scanButton.setOnClickListener {
            scanCaptureLauncher.launch(Intent(this, ScanCaptureActivity::class.java))
        }

        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun addCurrentInput() {
        val text = binding.barcodeInput.text?.toString() ?: return
        if (viewModel.addToList(text)) {
            binding.barcodeInput.text?.clear()
        }
    }

    private fun setupRecentList() {
        recentAdapter = RecentSearchAdapter { barcode -> viewModel.addHistoryToList(barcode) }
        binding.recentList.layoutManager = LinearLayoutManager(this)
        binding.recentList.adapter = recentAdapter
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
                    recentAdapter.submitList(history)
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
