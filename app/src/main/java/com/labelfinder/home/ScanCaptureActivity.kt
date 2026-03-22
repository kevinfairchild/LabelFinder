package com.labelfinder.home

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.labelfinder.BarcodeAnalyzer
import com.labelfinder.BarcodeOverlayView
import com.labelfinder.databinding.ActivityScanCaptureBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ScanCaptureActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanCaptureBinding
    private lateinit var cameraExecutor: ExecutorService
    private var barcodeAnalyzer: BarcodeAnalyzer? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val frozen = AtomicBoolean(false)

    private var detectedBarcodes: List<BarcodeAnalyzer.DetectedBarcode> = emptyList()
    private var lastImageWidth = 0
    private var lastImageHeight = 0

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

        binding.cancelButton.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        binding.rescanButton.setOnClickListener { rescan() }

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

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
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

            val analyzer = BarcodeAnalyzer(intArrayOf()) { barcodes, w, h ->
                if (barcodes.isNotEmpty() && frozen.compareAndSet(false, true)) {
                    detectedBarcodes = barcodes
                    lastImageWidth = w
                    lastImageHeight = h
                    runOnUiThread { freezeFrame() }
                }
            }
            barcodeAnalyzer = analyzer

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { it.setAnalyzer(cameraExecutor, analyzer) }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera failed: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun freezeFrame() {
        // Capture the preview as a bitmap
        val bitmap = binding.previewView.bitmap
        if (bitmap != null) {
            binding.frozenFrame.setImageBitmap(bitmap)
            binding.frozenFrame.visibility = View.VISIBLE
        }
        binding.previewView.visibility = View.INVISIBLE
        binding.scanningText.visibility = View.GONE

        // Set up overlay in selectable mode
        binding.overlayView.setSourceDimensions(lastImageWidth, lastImageHeight)
        val rects = detectedBarcodes.map {
            BarcodeOverlayView.BarcodeRect(it.boundingBox, it.rawValue, false)
        }
        binding.overlayView.isSelectableMode = true
        binding.overlayView.updateBarcodes(rects)

        // Show bottom panel
        binding.bottomPanel.visibility = View.VISIBLE
        binding.useSelectedButton.isEnabled = false
        binding.selectionText.text = "Tap barcodes to select"
    }

    private fun rescan() {
        frozen.set(false)
        detectedBarcodes = emptyList()
        binding.overlayView.isSelectableMode = false
        binding.overlayView.clear()
        binding.overlayView.clearSelection()
        binding.frozenFrame.visibility = View.GONE
        binding.frozenFrame.setImageBitmap(null)
        binding.previewView.visibility = View.VISIBLE
        binding.scanningText.visibility = View.VISIBLE
        binding.bottomPanel.visibility = View.GONE

        // Restart camera
        cameraProvider?.unbindAll()
        startCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        barcodeAnalyzer?.close()
        cameraExecutor.shutdown()
    }
}
