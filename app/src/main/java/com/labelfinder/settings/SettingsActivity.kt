package com.labelfinder.settings

import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.labelfinder.BarcodeUtils
import com.labelfinder.data.AppDatabase
import com.labelfinder.data.AppSettings
import com.labelfinder.data.SearchRepository
import com.labelfinder.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var repository: SearchRepository
    private var currentSettings = AppSettings()
    private var prefixList = mutableListOf<String>()
    private var suffixList = mutableListOf<String>()

    private val formatMap = linkedMapOf(
        "CODE_39" to "Code 39", "CODE_128" to "Code 128",
        "EAN_13" to "EAN-13", "EAN_8" to "EAN-8",
        "UPC_A" to "UPC-A", "UPC_E" to "UPC-E",
        "QR_CODE" to "QR Code", "DATA_MATRIX" to "Data Matrix",
        "PDF_417" to "PDF417", "AZTEC" to "Aztec"
    )
    private val volumeOptions = listOf(0 to "Off", 33 to "Low", 66 to "Medium", 100 to "High")
    private val vibrationOptions = listOf(0 to "Off", 1 to "Light", 2 to "Medium", 3 to "Strong")
    private val toneOptions = listOf(
        ToneGenerator.TONE_PROP_BEEP to "Beep",
        ToneGenerator.TONE_PROP_ACK to "Alert",
        ToneGenerator.TONE_PROP_PROMPT to "Confirmation",
        ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD to "Alarm"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = SearchRepository(AppDatabase.getInstance(this))

        binding.backButton.setOnClickListener { finish() }

        binding.clearHistoryButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear History")
                .setMessage("Remove all search history?")
                .setPositiveButton("Clear") { _, _ ->
                    lifecycleScope.launch {
                        repository.clearHistory()
                        Toast.makeText(this@SettingsActivity, "History cleared", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.addPrefixButton.setOnClickListener { addPrefix() }
        binding.addSuffixButton.setOnClickListener { addSuffix() }

        lifecycleScope.launch {
            currentSettings = repository.getSettings()
            prefixList = BarcodeUtils.parseList(currentSettings.prefixes).toMutableList()
            suffixList = BarcodeUtils.parseList(currentSettings.suffixes).toMutableList()
            setupFormatChips()
            setupVolumeChips()
            setupVibrationChips()
            setupToneChips()
            refreshPrefixChips()
            refreshSuffixChips()
        }
    }

    // ---- Prefix/Suffix management ----

    private fun addPrefix() {
        val input = binding.prefixInput.text?.toString() ?: return
        val updated = BarcodeUtils.addUnique(prefixList, input)
        if (updated.size == prefixList.size) {
            Snackbar.make(binding.root, "\"${input.trim()}\" is already in the list", Snackbar.LENGTH_SHORT).show()
            return
        }
        prefixList = updated.toMutableList()
        binding.prefixInput.text?.clear()
        refreshPrefixChips()
        savePrefixSuffix()
    }

    private fun addSuffix() {
        val input = binding.suffixInput.text?.toString() ?: return
        val updated = BarcodeUtils.addUnique(suffixList, input)
        if (updated.size == suffixList.size) {
            Snackbar.make(binding.root, "\"${input.trim()}\" is already in the list", Snackbar.LENGTH_SHORT).show()
            return
        }
        suffixList = updated.toMutableList()
        binding.suffixInput.text?.clear()
        refreshSuffixChips()
        savePrefixSuffix()
    }

    private fun refreshPrefixChips() {
        binding.prefixChipGroup.removeAllViews()
        for (prefix in prefixList) {
            val chip = Chip(this).apply {
                text = prefix
                typeface = Typeface.MONOSPACE
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    prefixList.remove(prefix)
                    refreshPrefixChips()
                    savePrefixSuffix()
                }
            }
            binding.prefixChipGroup.addView(chip)
        }
    }

    private fun refreshSuffixChips() {
        binding.suffixChipGroup.removeAllViews()
        for (suffix in suffixList) {
            val chip = Chip(this).apply {
                text = suffix
                typeface = Typeface.MONOSPACE
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    suffixList.remove(suffix)
                    refreshSuffixChips()
                    savePrefixSuffix()
                }
            }
            binding.suffixChipGroup.addView(chip)
        }
    }

    private fun savePrefixSuffix() {
        currentSettings = currentSettings.copy(
            prefixes = BarcodeUtils.serializeList(prefixList),
            suffixes = BarcodeUtils.serializeList(suffixList)
        )
        save()
    }

    // ---- Format/Volume/Vibration/Tone setup (unchanged) ----

    private fun setupFormatChips() {
        val enabled = currentSettings.enabledFormats.split(",").toSet()
        for ((key, label) in formatMap) {
            val chip = Chip(this).apply {
                text = label
                isCheckable = true
                isChecked = key in enabled
                setOnCheckedChangeListener { _, _ -> saveFormats() }
            }
            binding.formatChipGroup.addView(chip)
        }
    }

    private fun saveFormats() {
        val enabled = mutableListOf<String>()
        val keys = formatMap.keys.toList()
        for (i in 0 until binding.formatChipGroup.childCount) {
            val chip = binding.formatChipGroup.getChildAt(i) as Chip
            if (chip.isChecked) enabled.add(keys[i])
        }
        if (enabled.isEmpty()) {
            Toast.makeText(this, "At least one format required", Toast.LENGTH_SHORT).show()
            for (i in 0 until binding.formatChipGroup.childCount) {
                (binding.formatChipGroup.getChildAt(i) as Chip).isChecked = true
            }
            return
        }
        currentSettings = currentSettings.copy(enabledFormats = enabled.joinToString(","))
        save()
    }

    private fun setupVolumeChips() {
        for ((value, label) in volumeOptions) {
            val chip = Chip(this).apply {
                text = label
                isCheckable = true
                isChecked = value == currentSettings.alertVolume
                setOnClickListener {
                    currentSettings = currentSettings.copy(alertVolume = value)
                    save()
                }
            }
            binding.volumeChipGroup.addView(chip)
        }
    }

    private fun setupVibrationChips() {
        for ((value, label) in vibrationOptions) {
            val chip = Chip(this).apply {
                text = label
                isCheckable = true
                isChecked = value == currentSettings.vibrationStrength
                setOnClickListener {
                    currentSettings = currentSettings.copy(vibrationStrength = value)
                    save()
                }
            }
            binding.vibrationChipGroup.addView(chip)
        }
    }

    private fun setupToneChips() {
        for ((value, label) in toneOptions) {
            val chip = Chip(this).apply {
                text = label
                isCheckable = true
                isChecked = value == currentSettings.alertToneType
                setOnClickListener {
                    currentSettings = currentSettings.copy(alertToneType = value)
                    save()
                    try {
                        val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, currentSettings.alertVolume)
                        tg.startTone(value, 300)
                        binding.root.postDelayed({ tg.release() }, 500)
                    } catch (_: Exception) {}
                }
            }
            binding.toneChipGroup.addView(chip)
        }
    }

    private fun save() {
        lifecycleScope.launch { repository.saveSettings(currentSettings) }
    }
}
