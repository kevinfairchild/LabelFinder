package com.labelfinder.finder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.RectF
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
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.labelfinder.BarcodeAnalyzer
import com.labelfinder.BarcodeOverlayView
import com.labelfinder.DemoImageTouchHelper
import com.labelfinder.R
import com.labelfinder.data.AppDatabase
import com.labelfinder.data.SearchRepository
import com.labelfinder.databinding.ActivityFinderBinding
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FinderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BARCODES = "barcodes"
        const val EXTRA_PREFIXES = "prefixes"
        const val EXTRA_SUFFIXES = "suffixes"
        const val EXTRA_PARTIAL_MATCH = "partial_match"

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
    private var resolutionWarningShown = false
    private var toneGenerator: ToneGenerator? = null
    private lateinit var targetAdapter: TargetListAdapter
    private var alertVolume = 100
    private var vibrationStrength = 2
    private var alertToneType = ToneGenerator.TONE_PROP_BEEP

    // Confidence filter: require N detections before showing a barcode
    private val detectionCounts = mutableMapOf<String, Int>()
    private val confirmThreshold = 3 // must be seen in 3 frames to display

    private val viewModel: FinderViewModel by viewModels {
        val barcodes = intent.getStringArrayExtra(EXTRA_BARCODES)?.toList() ?: emptyList()
        val prefixes = intent.getStringArrayExtra(EXTRA_PREFIXES)?.toList() ?: emptyList()
        val suffixes = intent.getStringArrayExtra(EXTRA_SUFFIXES)?.toList() ?: emptyList()
        val partialMatch = intent.getBooleanExtra(EXTRA_PARTIAL_MATCH, false)
        FinderViewModel.Factory(barcodes, prefixes, suffixes, partialMatch)
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

        setupButtons()
        setupTargetList()
        observeState()

        // Load alert settings from DB, then start camera
        lifecycleScope.launch {
            val repo = SearchRepository(AppDatabase.getInstance(this@FinderActivity))
            val settings = repo.getSettings()
            alertVolume = settings.alertVolume
            vibrationStrength = settings.vibrationStrength
            alertToneType = settings.alertToneType
            // Start camera after settings are loaded
            if (isEmulator()) {
                loadDemoImage()
            } else if (ContextCompat.checkSelfPermission(this@FinderActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
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

    private fun setupTargetList() {
        targetAdapter = TargetListAdapter(
            onMarkFound = { viewModel.markFound(it) },
            onUnmark = { viewModel.unmarkFound(it) }
        )
        binding.targetList.layoutManager = LinearLayoutManager(this)
        binding.targetList.adapter = targetAdapter
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
        targetAdapter.submitList(state.targets.toList())

        if (state.allFound) {
            binding.multiDoneButton.text = "All Found \u2014 Done"
        } else {
            binding.multiDoneButton.text = "Done"
        }
    }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("emulator") ||
        Build.MODEL.contains("Emulator") || Build.MODEL.contains("Android SDK") ||
        Build.PRODUCT.contains("sdk") || Build.HARDWARE.contains("ranchu")

    private fun loadDemoImage() {
        val bitmap = try {
            val options = BitmapFactory.Options().apply {
                inSampleSize = 2
            }
            assets.open("demo_barcodes.png").use { BitmapFactory.decodeStream(it, null, options) }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to load demo image", Toast.LENGTH_SHORT).show()
            return
        }
        if (bitmap == null) {
            Toast.makeText(this, "Failed to decode demo image", Toast.LENGTH_SHORT).show()
            return
        }
        binding.previewView.visibility = View.GONE
        binding.staticImage.setImageBitmap(bitmap)
        binding.staticImage.visibility = View.VISIBLE
        binding.torchButton.visibility = View.GONE

        DemoImageTouchHelper(binding.overlayView, binding.staticImage, binding.overlayView).attach()

        val inputImage = InputImage.fromBitmap(bitmap, 0)
        BarcodeScanning.getClient().process(inputImage)
            .addOnSuccessListener { barcodes ->
                val detected = barcodes.mapNotNull { barcode ->
                    val box = barcode.boundingBox ?: return@mapNotNull null
                    val value = barcode.rawValue ?: return@mapNotNull null
                    BarcodeAnalyzer.DetectedBarcode(
                        rawValue = value,
                        boundingBox = RectF(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat())
                    )
                }
                // Pre-seed confidence counts so barcodes show immediately
                detected.forEach { detectionCounts[it.rawValue] = confirmThreshold }
                binding.overlayView.setSourceDimensions(bitmap.width, bitmap.height)
                handleDetectedBarcodes(detected, bitmap.width, bitmap.height)
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Barcode scan failed: ${e.message}", Toast.LENGTH_LONG).show()
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

            var errorShown = false
            val analyzer = BarcodeAnalyzer(
                formats = intArrayOf(),
                onBarcodesDetected = { barcodes, w, h ->
                    runOnUiThread {
                        if (!resolutionWarningShown) {
                            resolutionWarningShown = true
                            if (w < 1920 || h < 1080) {
                                Toast.makeText(this, "Low camera resolution (${w}x${h}) — scanning performance may be reduced", Toast.LENGTH_LONG).show()
                            }
                        }
                        handleDetectedBarcodes(barcodes, w, h)
                    }
                },
                onError = { e ->
                    if (!errorShown) {
                        errorShown = true
                        runOnUiThread {
                            Toast.makeText(this, "Barcode scanner: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )
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

        // Update detection counts: increment for seen values, decay for unseen
        val currentValues = detected.map { it.rawValue }.toSet()
        for (value in currentValues) {
            detectionCounts[value] = (detectionCounts[value] ?: 0) + 1
        }
        // Decay values not seen this frame; remove if they drop to 0
        val toRemove = mutableListOf<String>()
        for ((value, count) in detectionCounts) {
            if (value !in currentValues) {
                val newCount = count - 1
                if (newCount <= 0) toRemove.add(value)
                else detectionCounts[value] = newCount
            }
        }
        toRemove.forEach { detectionCounts.remove(it) }

        // Only use confirmed barcodes (seen >= threshold frames)
        val confirmedValues = detectionCounts.filter { it.value >= confirmThreshold }.keys
        val confirmedDetections = detected.filter { it.rawValue in confirmedValues }

        val scannedValues = confirmedDetections.map { it.rawValue }
        val newlySpotted = viewModel.onBarcodesDetected(scannedValues)

        // Build overlay rects only for confirmed barcodes
        val rects = confirmedDetections.map { barcode ->
            val targetIdx = viewModel.matchingTargetIndex(barcode.rawValue)
            val isTarget = targetIdx >= 0
            val color = if (targetIdx >= 0) TARGET_COLORS[targetIdx % TARGET_COLORS.size] else 0
            BarcodeOverlayView.BarcodeRect(barcode.boundingBox, barcode.rawValue, isTarget, color)
        }
        binding.overlayView.updateBarcodes(rects)

        // Trigger alerts for newly spotted targets
        if (newlySpotted.isNotEmpty()) {
            triggerAlert()
        }
    }

    private fun triggerAlert() {
        if (alertVolume > 0) {
            try {
                // Use a fresh ToneGenerator each time for reliability
                val tg = ToneGenerator(AudioManager.STREAM_ALARM, alertVolume)
                tg.startTone(alertToneType, 500)
                binding.root.postDelayed({ tg.release() }, 700)
            } catch (_: Exception) {}
        }
        if (vibrationStrength > 0) {
            try {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }
                val duration = when (vibrationStrength) { 1 -> 200L; 2 -> 400L; else -> 600L }
                val effect = VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    vibrator.vibrate(effect, android.os.VibrationAttributes.createForUsage(
                        android.os.VibrationAttributes.USAGE_ALARM
                    ))
                } else {
                    vibrator.vibrate(effect)
                }
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        barcodeAnalyzer?.close()
        cameraExecutor.shutdown()
    }
}
