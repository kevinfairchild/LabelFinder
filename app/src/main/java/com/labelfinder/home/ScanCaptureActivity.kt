package com.labelfinder.home

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.labelfinder.BarcodeAnalyzer
import com.labelfinder.BarcodeOverlayView

import com.labelfinder.R
import com.labelfinder.databinding.ActivityScanCaptureBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ScanCaptureActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanCaptureBinding
    private lateinit var cameraExecutor: ExecutorService
    private var barcodeAnalyzer: BarcodeAnalyzer? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var isTorchOn = false
    private var isFrozen = false
    private var resolutionWarningShown = false

    private var detectedBarcodes: List<BarcodeAnalyzer.DetectedBarcode> = emptyList()
    private var lastImageWidth = 0
    private var lastImageHeight = 0

    // Confidence filter: require N detections before showing a barcode
    private val detectionCounts = mutableMapOf<String, Int>()
    private val confirmThreshold = 3

    companion object {
        const val RESULT_BARCODE = "barcode"
        const val RESULT_BARCODES = "barcodes"
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else { Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show(); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Live panel buttons
        binding.liveCancelButton.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        binding.captureButton.setOnClickListener { freezeFrame() }

        // Torch
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

        // Frozen panel buttons
        binding.rescanButton.setOnClickListener { rescan() }
        binding.cancelButton.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        binding.useSelectedButton.setOnClickListener {
            val selected = binding.overlayView.getSelectedValues()
            if (selected.isNotEmpty()) {
                val result = Intent().apply {
                    putExtra(RESULT_BARCODE, selected.first())
                    putExtra(RESULT_BARCODES, selected.toTypedArray())
                }
                setResult(Activity.RESULT_OK, result)
                finish()
            }
        }

        binding.overlayView.onSelectionChanged = { selected ->
            val count = selected.size
            binding.useSelectedButton.isEnabled = count > 0
            binding.selectionText.text = when (count) {
                0 -> "Tap barcodes to select"
                1 -> "1 barcode selected"
                else -> "$count barcodes selected"
            }
        }

        if (isEmulator()) {
            loadDemoImage()
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
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
        binding.frozenFrame.setImageBitmap(bitmap)
        binding.frozenFrame.visibility = View.VISIBLE
        binding.torchButton.visibility = View.GONE


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
                detectedBarcodes = detected
                lastImageWidth = bitmap.width
                lastImageHeight = bitmap.height
                binding.overlayView.setSourceDimensions(bitmap.width, bitmap.height)
                val rects = detected.map { BarcodeOverlayView.BarcodeRect(it.boundingBox, it.rawValue, false) }
                binding.overlayView.updateBarcodes(rects)
                binding.captureButton.isEnabled = detected.isNotEmpty()
                binding.scanningText.text = when (detected.size) {
                    0 -> "No barcodes detected"
                    1 -> "1 barcode detected"
                    else -> "${detected.size} barcodes detected"
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Barcode scan failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

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
                        if (!isFrozen) {
                            detectedBarcodes = barcodes
                            lastImageWidth = w
                            lastImageHeight = h
                            updateLivePreview(barcodes, w, h)
                        }
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

            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        android.util.Size(1920, 1080),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    )
                ).build()

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

    private fun updateLivePreview(barcodes: List<BarcodeAnalyzer.DetectedBarcode>, w: Int, h: Int) {
        binding.overlayView.setSourceDimensions(w, h)

        // Update detection counts
        val currentValues = barcodes.map { it.rawValue }.toSet()
        for (value in currentValues) {
            detectionCounts[value] = (detectionCounts[value] ?: 0) + 1
        }
        val toRemove = mutableListOf<String>()
        for ((value, count) in detectionCounts) {
            if (value !in currentValues) {
                val newCount = count - 1
                if (newCount <= 0) toRemove.add(value)
                else detectionCounts[value] = newCount
            }
        }
        toRemove.forEach { detectionCounts.remove(it) }

        // Only show confirmed barcodes
        val confirmedValues = detectionCounts.filter { it.value >= confirmThreshold }.keys
        val confirmed = barcodes.filter { it.rawValue in confirmedValues }

        // Update detectedBarcodes for freeze frame
        detectedBarcodes = confirmed

        val rects = confirmed.map {
            BarcodeOverlayView.BarcodeRect(it.boundingBox, it.rawValue, false)
        }
        binding.overlayView.updateBarcodes(rects)

        val count = confirmed.size
        binding.captureButton.isEnabled = count > 0
        binding.scanningText.text = when (count) {
            0 -> "Point at barcodes\u2026"
            1 -> "1 barcode detected"
            else -> "$count barcodes detected"
        }
    }

    private fun freezeFrame() {
        if (detectedBarcodes.isEmpty()) return
        isFrozen = true

        val bitmap = binding.previewView.bitmap
        if (bitmap != null) {
            binding.frozenFrame.setImageBitmap(bitmap)
            binding.frozenFrame.visibility = View.VISIBLE
        }
        binding.previewView.visibility = View.INVISIBLE
        binding.livePanel.visibility = View.GONE
        binding.torchButton.visibility = View.GONE

        // Switch overlay to selectable mode
        binding.overlayView.setSourceDimensions(lastImageWidth, lastImageHeight)
        val rects = detectedBarcodes.map {
            BarcodeOverlayView.BarcodeRect(it.boundingBox, it.rawValue, false)
        }
        binding.overlayView.isSelectableMode = true
        binding.overlayView.updateBarcodes(rects)

        // Show frozen panel
        binding.bottomPanel.visibility = View.VISIBLE
        binding.useSelectedButton.isEnabled = false
        binding.selectionText.text = "Tap barcodes to select"
    }

    private fun rescan() {
        isFrozen = false
        detectedBarcodes = emptyList()
        detectionCounts.clear()
        binding.overlayView.isSelectableMode = false
        binding.overlayView.clear()
        binding.overlayView.clearSelection()
        binding.frozenFrame.visibility = View.GONE
        binding.frozenFrame.setImageBitmap(null)
        binding.previewView.visibility = View.VISIBLE
        binding.livePanel.visibility = View.VISIBLE
        binding.torchButton.visibility = View.VISIBLE
        binding.bottomPanel.visibility = View.GONE
        binding.captureButton.isEnabled = false
        binding.scanningText.text = "Point at barcodes\u2026"
        isTorchOn = false
        binding.torchButton.setImageResource(R.drawable.ic_flashlight_off)

        cameraProvider?.unbindAll()
        startCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        barcodeAnalyzer?.close()
        cameraExecutor.shutdown()
    }
}
