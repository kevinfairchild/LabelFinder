package com.labelfinder

import android.graphics.RectF
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage

class BarcodeAnalyzer(
    formats: IntArray,
    private val onBarcodesDetected: (
        barcodes: List<DetectedBarcode>,
        imageWidth: Int,
        imageHeight: Int
    ) -> Unit
) : ImageAnalysis.Analyzer {

    data class DetectedBarcode(
        val rawValue: String,
        val boundingBox: RectF
    )

    private val options = if (formats.size >= 2) {
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(formats[0], *formats.drop(1).toIntArray())
            .build()
    } else if (formats.size == 1) {
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(formats[0])
            .build()
    } else {
        BarcodeScannerOptions.Builder().build()
    }

    private val scanner = BarcodeScanning.getClient(options)

    fun close() { scanner.close() }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        val rotatedWidth: Int
        val rotatedHeight: Int
        if (imageProxy.imageInfo.rotationDegrees == 90 || imageProxy.imageInfo.rotationDegrees == 270) {
            rotatedWidth = imageProxy.height
            rotatedHeight = imageProxy.width
        } else {
            rotatedWidth = imageProxy.width
            rotatedHeight = imageProxy.height
        }

        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                val detected = barcodes.mapNotNull { barcode ->
                    val box = barcode.boundingBox ?: return@mapNotNull null
                    val value = barcode.rawValue ?: return@mapNotNull null
                    DetectedBarcode(
                        rawValue = value,
                        boundingBox = RectF(
                            box.left.toFloat(),
                            box.top.toFloat(),
                            box.right.toFloat(),
                            box.bottom.toFloat()
                        )
                    )
                }
                onBarcodesDetected(detected, rotatedWidth, rotatedHeight)
            }
            .addOnFailureListener { e -> Log.w("BarcodeAnalyzer", "Barcode scanning failed", e) }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }
}
