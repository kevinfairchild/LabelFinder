package com.labelfinder.finder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import com.labelfinder.BarcodeAnalyzer
import com.labelfinder.BarcodeOverlayView
import com.labelfinder.R
import com.labelfinder.databinding.ActivityFinderBinding
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FinderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BARCODES = "barcodes"
        const val EXTRA_STRIP_CHARS = "strip_chars"

        // Distinct colors for multi-search overlay highlights
        val TARGET_COLORS = intArrayOf(
            0xFF4CAF50.toInt(), // green
            0xFF2196F3.toInt(), // blue
            0xFFFF9800.toInt(), // orange
            0xFFE91E63.toInt(), // pink
            0xFF9C27B0.toInt(), // purple
            0xFF00BCD4.toInt(), // cyan
            0xFFFFEB3B.toInt(), // yellow
            0xFFFF5722.toInt()  // deep orange
        )
    }

    private lateinit var binding: ActivityFinderBinding
    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null
    private var barcodeAnalyzer: BarcodeAnalyzer? = null
    private var isTorchOn = false
    private var toneGenerator: ToneGenerator? = null

    private val viewModel: FinderViewModel by viewModels {
        val barcodes = intent.getStringArrayExtra(EXTRA_BARCODES)?.toList() ?: emptyList()
        val strip = intent.getStringExtra(EXTRA_STRIP_CHARS) ?: "*+"
        FinderViewModel.Factory(barcodes, strip)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else { Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show(); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFinderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        toneGenerator = try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100) } catch (_: Exception) { null }

        setupButtons()
        observeState()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupButtons() {
        binding.doneButton.setOnClickListener { finish() }
        binding.multiDoneButton.setOnClickListener { finish() }
        binding.torchButton.setOnClickListener {
            camera?.let { cam ->
                if (cam.cameraInfo.hasFlashUnit()) {
                    isTorchOn = !isTorchOn
                    cam.cameraControl.enableTorch(isTorchOn)
                    binding.torchButton.setImageResource(
                        if (isTorchOn) R.drawable.ic_flashlight_on else R.drawable.ic_flashlight_off
                    )
                }
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    if (state.isMultiSearch) {
                        binding.singleSearchPanel.visibility = View.GONE
                        binding.multiSearchPanel.visibility = View.VISIBLE
                        updateMultiSearchUI(state)
                    } else {
                        binding.singleSearchPanel.visibility = View.VISIBLE
                        binding.multiSearchPanel.visibility = View.GONE
                        binding.singleSearchLabel.text = state.targets.firstOrNull()?.barcode ?: ""
                    }
                }
            }
        }
    }

    private fun updateMultiSearchUI(state: FinderUiState) {
        binding.progressText.text = "${state.foundCount} of ${state.totalCount} found"

        binding.targetChipGroup.removeAllViews()
        for ((i, target) in state.targets.withIndex()) {
            val color = TARGET_COLORS[i % TARGET_COLORS.size]
            val chip = Chip(this).apply {
                text = when (target.status) {
                    TargetStatus.FOUND -> "\u2713 ${target.barcode}"
                    TargetStatus.SPOTTED -> "\u25C9 ${target.barcode}"
                    TargetStatus.SEARCHING -> target.barcode
                }
                typeface = Typeface.MONOSPACE
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                    if (target.status == TargetStatus.FOUND) 0xFF1B5E20.toInt()
                    else (color and 0x00FFFFFF) or 0x40000000 // 25% alpha tint
                )
                setTextColor(if (target.status == TargetStatus.FOUND) 0xFFA5D6A7.toInt() else 0xFFFFFFFF.toInt())
                setOnClickListener {
                    if (target.status == TargetStatus.FOUND) viewModel.unmarkFound(i)
                    else if (target.status == TargetStatus.SPOTTED) viewModel.markFound(i)
                }
            }
            binding.targetChipGroup.addView(chip)
        }

        if (state.allFound) {
            binding.multiDoneButton.text = "All Found — Done"
        } else {
            binding.multiDoneButton.text = "Done"
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        android.util.Size(1920, 1080),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    )
                ).build()

            val analyzer = BarcodeAnalyzer(intArrayOf()) { barcodes, w, h ->
                runOnUiThread { handleDetectedBarcodes(barcodes, w, h) }
            }
            barcodeAnalyzer = analyzer

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { it.setAnalyzer(cameraExecutor, analyzer) }

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera failed: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleDetectedBarcodes(
        detected: List<BarcodeAnalyzer.DetectedBarcode>,
        imageWidth: Int, imageHeight: Int
    ) {
        binding.overlayView.setSourceDimensions(imageWidth, imageHeight)

        val state = viewModel.state.value
        val scannedValues = detected.map { it.rawValue }
        val newlySpotted = viewModel.onBarcodesDetected(scannedValues)

        // Build overlay rects
        val rects = detected.map { barcode ->
            val targetIdx = viewModel.matchingTargetIndex(barcode.rawValue)
            val isTarget = targetIdx >= 0
            BarcodeOverlayView.BarcodeRect(barcode.boundingBox, barcode.rawValue, isTarget)
        }
        binding.overlayView.updateBarcodes(rects)

        // Trigger alerts for newly spotted targets
        if (newlySpotted.isNotEmpty()) {
            triggerAlert()
        }
    }

    private fun triggerAlert() {
        try { toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 300) } catch (_: Exception) {}
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(
                    VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(200)
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        barcodeAnalyzer?.close()
        cameraExecutor.shutdown()
        toneGenerator?.release()
    }
}
