package com.labelfinder

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.Editable
import android.text.TextWatcher
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.labelfinder.databinding.ActivityMainBinding
import com.google.mlkit.vision.barcode.common.Barcode
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var prefs: SharedPreferences

    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var targetBarcode: String = ""
    private var isTorchOn = false
    private var isMultiMode = false
    private var isScanInputFrozen = false

    private val alertCooldownMs = 1500L
    private var lastAlertTimeMs: Long = 0
    private var toneGenerator: ToneGenerator? = null

    private var enabledFormats: MutableSet<String> = mutableSetOf(
        "CODE_39", "CODE_128", "EAN_13", "EAN_8", "UPC_A", "UPC_E",
        "QR_CODE", "DATA_MATRIX", "PDF_417", "AZTEC"
    )

    private var alertVolume: Int = 100
    private var vibrationStrength: Int = 2
    private var alertToneType: Int = ToneGenerator.TONE_PROP_BEEP
    private var stripChars: String = "*+"

    private val scanHistory = mutableListOf<ScanHistoryEntry>()
    private val searchList = mutableListOf<SearchListItem>()

    // Debounce state machine
    private var matchCount = 0
    private var lossCount = 0
    private var isFrozen = false
    private val matchThreshold = 3
    private val lossThreshold = 20
    private var pendingMatchRects: List<BarcodeOverlayView.BarcodeRect> = emptyList()

    // Last frame data for scan input
    private var lastDetectedBarcodes: List<BarcodeAnalyzer.DetectedBarcode> = emptyList()
    private var lastImageWidth = 0
    private var lastImageHeight = 0
    // Frozen scan input state for redrawing on selection change
    private var scanInputBaseBitmap: Bitmap? = null
    private var scanInputBarcodeRects: List<BarcodeOverlayView.BarcodeRect> = emptyList()

    data class ScanHistoryEntry(val barcode: String, val timestamp: Long, var found: Boolean)
    data class SearchListItem(val barcode: String, val addedAt: Long, var found: Boolean = false, var active: Boolean = true)

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) startCamera()
        else { Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show(); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("barcode_finder", Context.MODE_PRIVATE)
        cameraExecutor = Executors.newSingleThreadExecutor()

        loadSettings(); loadHistory(); loadSearchList()
        recreateToneGenerator()
        setupSearchInput(); setupCameraControls(); setupFreezeButtons(); setupScanInput()
        refreshSearchListUI()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            startCamera()
        else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun recreateToneGenerator() {
        toneGenerator?.release()
        toneGenerator = try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, alertVolume) } catch (_: Exception) { null }
    }

    // ---- Search Input ----

    private fun setupSearchInput() {
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString()?.trim() ?: ""
                if (!isMultiMode) targetBarcode = text
                binding.clearButton.visibility = if (text.isNotEmpty()) View.VISIBLE else View.GONE
                resetDebounce(); updateStatusText()
                if (text.isEmpty() && !isMultiMode) binding.overlayView.clear()
            }
        })

        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val text = binding.searchInput.text?.toString()?.trim() ?: ""
                if (text.isNotEmpty()) {
                    if (isMultiMode) { addToSearchList(text); binding.searchInput.text?.clear() }
                    else { targetBarcode = text; addToHistory(text) }
                }
                resetDebounce(); hideKeyboard(); true
            } else false
        }

        binding.clearButton.setOnClickListener {
            binding.searchInput.text?.clear()
            if (!isMultiMode) binding.overlayView.clear()
            resetDebounce()
        }

        binding.historyButton.setOnClickListener { showHistoryDialog() }
        binding.settingsButton.setOnClickListener { showSettingsDialog() }

        binding.batchToggle.setOnClickListener {
            isMultiMode = !isMultiMode; resetDebounce(); updateMultiModeUI()
        }

        binding.clearAllButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear All")
                .setMessage("Remove all items from the search list?")
                .setPositiveButton("Clear") { _, _ ->
                    searchList.forEach { it.active = false }
                    saveSearchList(); refreshSearchListUI(); updateStatusText()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun updateMultiModeUI() {
        if (isMultiMode) {
            binding.batchToggle.setImageResource(android.R.drawable.ic_menu_agenda)
            binding.batchToggle.alpha = 1f
            binding.searchInputLayout.hint = "Add to list"
            targetBarcode = ""
            binding.batchPanel.visibility = View.VISIBLE
            refreshSearchListUI()
        } else {
            binding.batchToggle.setImageResource(android.R.drawable.ic_menu_add)
            binding.batchToggle.alpha = 0.6f
            binding.searchInputLayout.hint = "Search barcode"
            targetBarcode = binding.searchInput.text?.toString()?.trim() ?: ""
            binding.batchPanel.visibility = if (searchList.any { it.active }) View.VISIBLE else View.GONE
        }
        updateStatusText()
    }

    // ---- Search List (Multi Search) ----

    private fun addToSearchList(barcode: String) {
        val existing = searchList.firstOrNull { it.barcode.equals(barcode, ignoreCase = true) && it.active }
        if (existing != null) { Toast.makeText(this, "Already in list", Toast.LENGTH_SHORT).show(); return }
        val dismissed = searchList.firstOrNull { it.barcode.equals(barcode, ignoreCase = true) && !it.active }
        if (dismissed != null) { dismissed.active = true; dismissed.found = false }
        else searchList.add(SearchListItem(barcode, System.currentTimeMillis()))
        addToHistory(barcode); saveSearchList(); refreshSearchListUI()
    }

    private fun refreshSearchListUI() {
        binding.batchItemsContainer.removeAllViews()
        val activeItems = searchList.filter { it.active }
        if (activeItems.isEmpty()) {
            binding.batchPanel.visibility = View.GONE
            return
        }
        binding.batchPanel.visibility = View.VISIBLE
        val foundCount = activeItems.count { it.found }
        binding.batchCountText.text = "Searching: $foundCount/${activeItems.size} found"
        binding.clearAllButton.visibility = View.VISIBLE

        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt() }

        for (item in activeItems) {
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(6), dp(4), dp(6))
                setBackgroundColor(if (item.found) Color.parseColor("#CC1B5E20") else Color.parseColor("#CC37474F"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(4), 0, dp(4), 0) }
            }
            val label = TextView(this).apply {
                text = "${if (item.found) "\u2713 " else "\u2022 "}${item.barcode}"
                setTextColor(if (item.found) Color.parseColor("#A5D6A7") else Color.WHITE)
                textSize = 13f; typeface = if (item.found) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; maxLines = 1
            }
            val dismissBtn = ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                setBackgroundColor(Color.TRANSPARENT); setPadding(dp(6), dp(2), dp(6), dp(2))
                contentDescription = "Remove ${item.barcode}"
                setOnClickListener { item.active = false; saveSearchList(); refreshSearchListUI(); updateStatusText() }
            }
            chip.addView(label); chip.addView(dismissBtn)
            binding.batchItemsContainer.addView(chip)
        }
    }

    // ---- Matching ----

    private fun barcodeMatches(scannedValue: String, searchTerm: String): Boolean {
        if (stripChars.isEmpty()) return scannedValue.equals(searchTerm, ignoreCase = true)
        val stripSet = stripChars.toSet()
        val shouldStripPrefix = searchTerm.firstOrNull()?.let { it !in stripSet } ?: true
        val shouldStripSuffix = searchTerm.lastOrNull()?.let { it !in stripSet } ?: true
        var normalized = scannedValue
        if (shouldStripPrefix) normalized = normalized.trimStart { it in stripSet }
        if (shouldStripSuffix) normalized = normalized.trimEnd { it in stripSet }
        return normalized.equals(searchTerm, ignoreCase = true)
    }

    // ---- Found Lock-On ----

    private fun setupFreezeButtons() {
        binding.keepLookingButton.setOnClickListener { dismissFreezeFrame() }
        binding.markFoundButton.setOnClickListener {
            for (rect in pendingMatchRects) {
                for (item in searchList.filter { it.active && !it.found }) {
                    // Try both directions: scanned→search and search→scanned
                    if (barcodeMatches(rect.value, item.barcode) ||
                        barcodeMatches(item.barcode, rect.value)) {
                        item.found = true
                    }
                }
                for (entry in scanHistory.filter { !it.found }) {
                    if (barcodeMatches(rect.value, entry.barcode) ||
                        barcodeMatches(entry.barcode, rect.value)) {
                        entry.found = true
                    }
                }
            }
            saveSearchList(); saveHistory()
            if (!isMultiMode && targetBarcode.isNotEmpty() &&
                pendingMatchRects.any { barcodeMatches(it.value, targetBarcode) }) {
                binding.searchInput.text?.clear()
            }
            refreshSearchListUI()
            dismissFreezeFrame()
        }
    }

    // ---- Scan Input (Camera-based barcode input) ----

    private fun setupScanInput() {
        binding.scanInputButton.setOnClickListener {
            if (isScanInputFrozen) exitScanInput() else enterScanInput()
        }

        binding.scanInputCancelButton.setOnClickListener { exitScanInput() }

        binding.scanInputSearchButton.setOnClickListener {
            val selected = getSelectedScanInputValues()
            if (selected.isEmpty()) return@setOnClickListener
            val barcode = selected.first()
            exitScanInput()
            if (isMultiMode) { isMultiMode = false; updateMultiModeUI() }
            binding.searchInput.setText(barcode)
            targetBarcode = barcode
            addToHistory(barcode)
            hideKeyboard()
        }

        binding.scanInputAddToListButton.setOnClickListener {
            val selected = getSelectedScanInputValues()
            if (selected.isEmpty()) return@setOnClickListener
            for (value in selected) addToSearchList(value)
            exitScanInput()
            if (!isMultiMode) { isMultiMode = true; updateMultiModeUI() }
        }
    }

    private fun getSelectedScanInputValues(): List<String> {
        return scanInputSelectedIndices.filter { it < scanInputBarcodeRects.size }
            .map { scanInputBarcodeRects[it].value }
    }

    private fun updateScanInputButtons(count: Int) {
        binding.scanInputSearchButton.visibility = if (count == 1) View.VISIBLE else View.GONE
        binding.scanInputAddToListButton.visibility = if (count >= 1) View.VISIBLE else View.GONE
        binding.statusText.text = when (count) {
            0 -> "Tap barcodes to select \u2014 0 selected"
            1 -> "1 selected \u2014 Search or Add to List"
            else -> "$count selected \u2014 Add to List"
        }
    }

    // ---- Frozen Frame Display ----

    private var frozenFrameMatrix = Matrix()
    private var frozenBaseMatrix = Matrix()

    private fun showFrozenBitmap(bitmap: Bitmap?) {
        if (bitmap != null) {
            binding.frozenFrameView.setImageBitmap(bitmap)
        }
        binding.frozenFrameView.visibility = View.VISIBLE
        binding.previewView.visibility = View.INVISIBLE

        // Compute initial matrix to fill the view (matches PreviewView scaling)
        binding.frozenFrameView.post {
            val drawable = binding.frozenFrameView.drawable ?: return@post
            val vw = binding.frozenFrameView.width.toFloat()
            val vh = binding.frozenFrameView.height.toFloat()
            val bw = drawable.intrinsicWidth.toFloat()
            val bh = drawable.intrinsicHeight.toFloat()
            val scale = maxOf(vw / bw, vh / bh)
            val dx = (vw - bw * scale) / 2f
            val dy = (vh - bh * scale) / 2f

            frozenBaseMatrix = Matrix().apply { setScale(scale, scale); postTranslate(dx, dy) }
            frozenFrameMatrix = Matrix(frozenBaseMatrix)
            binding.frozenFrameView.imageMatrix = frozenFrameMatrix
        }

        // Setup pan/zoom gesture handler
        setupScanInputTouchHandler()
    }

    private fun hideFrozenFrame() {
        binding.frozenFrameView.visibility = View.GONE
        binding.frozenFrameView.setImageBitmap(null)
        binding.frozenFrameView.setOnTouchListener(null)
        binding.previewView.visibility = View.VISIBLE
    }

    private fun enterScanInput() {
        if (lastDetectedBarcodes.isEmpty()) {
            Toast.makeText(this, "No barcodes detected \u2014 point at barcodes first", Toast.LENGTH_SHORT).show()
            return
        }

        isScanInputFrozen = true

        // Capture exactly what's in the preview
        scanInputBaseBitmap = binding.previewView.bitmap
        if (scanInputBaseBitmap == null) {
            Toast.makeText(this, "Could not capture frame", Toast.LENGTH_SHORT).show()
            isScanInputFrozen = false
            return
        }

        // Map barcode rects from analysis coordinates to preview bitmap coordinates
        scanInputBarcodeRects = lastDetectedBarcodes.map { barcode ->
            val bw = scanInputBaseBitmap!!.width.toFloat()
            val bh = scanInputBaseBitmap!!.height.toFloat()
            val sw = lastImageWidth.toFloat()
            val sh = lastImageHeight.toFloat()
            val scale = maxOf(bw / sw, bh / sh)
            val ox = (bw - sw * scale) / 2f
            val oy = (bh - sh * scale) / 2f
            val mapped = RectF(
                barcode.boundingBox.left * scale + ox,
                barcode.boundingBox.top * scale + oy,
                barcode.boundingBox.right * scale + ox,
                barcode.boundingBox.bottom * scale + oy
            )
            BarcodeOverlayView.BarcodeRect(mapped, barcode.rawValue, false)
        }

        // Hide live overlay, we'll draw on the bitmap
        binding.overlayView.clear()
        binding.overlayView.visibility = View.GONE

        // Draw initial state (nothing selected)
        redrawScanInputBitmap(emptySet())
        showFrozenBitmap(null) // just setup gestures, bitmap already set

        binding.frozenFrameView.visibility = View.VISIBLE
        binding.previewView.visibility = View.INVISIBLE

        // Setup touch on the frozen frame for both pan/zoom AND barcode selection
        setupScanInputTouchHandler()

        binding.scanInputActions.visibility = View.VISIBLE
        binding.scanInputSearchButton.visibility = View.GONE
        binding.scanInputAddToListButton.visibility = View.GONE
        binding.scanInputButton.alpha = 1f

        binding.statusText.text = "Tap barcodes to select \u2014 0 selected"
        binding.statusText.setBackgroundResource(R.drawable.status_background)
    }

    private val scanInputSelectedIndices = mutableSetOf<Int>()

    private fun redrawScanInputBitmap(selected: Set<Int>) {
        val base = scanInputBaseBitmap ?: return
        val bmp = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bmp)

        val strokePaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; isAntiAlias = true }
        val fillPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
        val textPaint = Paint().apply { textSize = 32f; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD; setShadowLayer(4f, 2f, 2f, Color.BLACK) }
        val bgPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }

        for (i in scanInputBarcodeRects.indices) {
            val rect = scanInputBarcodeRects[i].rect
            val value = scanInputBarcodeRects[i].value
            val isSel = i in selected

            if (isSel) {
                fillPaint.color = Color.parseColor("#334CAF50")
                canvas.drawRoundRect(rect, 10f, 10f, fillPaint)
                strokePaint.color = Color.parseColor("#4CAF50"); strokePaint.strokeWidth = 6f
            } else {
                strokePaint.color = Color.parseColor("#90CAF9"); strokePaint.strokeWidth = 4f
            }
            canvas.drawRoundRect(rect, 10f, 10f, strokePaint)

            // Label
            val label = if (isSel) "\u2713 $value" else value
            textPaint.color = if (isSel) Color.parseColor("#4CAF50") else Color.WHITE
            textPaint.textSize = 30f
            val tw = textPaint.measureText(label)
            val tx = (rect.centerX() - tw / 2f).coerceAtLeast(8f)
            val ty = (rect.top - 10f).coerceAtLeast(textPaint.textSize + 8f)
            bgPaint.color = if (isSel) Color.parseColor("#DD1B5E20") else Color.parseColor("#CC000000")
            canvas.drawRoundRect(RectF(tx - 8f, ty - textPaint.textSize - 4f, tx + tw + 8f, ty + 6f), 8f, 8f, bgPaint)
            canvas.drawText(label, tx, ty, textPaint)
        }

        binding.frozenFrameView.setImageBitmap(bmp)
        // Reapply the current matrix so zoom/pan is preserved
        binding.frozenFrameView.imageMatrix = frozenFrameMatrix
    }

    private fun setupScanInputTouchHandler() {
        val scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    frozenFrameMatrix.postScale(detector.scaleFactor, detector.scaleFactor, detector.focusX, detector.focusY)
                    binding.frozenFrameView.imageMatrix = frozenFrameMatrix
                    return true
                }
            }
        )

        var lastX = 0f; var lastY = 0f
        var activePointerId = -1
        var isDragging = false

        binding.frozenFrameView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)

            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    lastX = event.x; lastY = event.y
                    activePointerId = event.getPointerId(0)
                    isDragging = false
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress) {
                        val idx = event.findPointerIndex(activePointerId)
                        if (idx >= 0) {
                            val dx = event.getX(idx) - lastX
                            val dy = event.getY(idx) - lastY
                            if (!isDragging && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) isDragging = true
                            if (isDragging) {
                                frozenFrameMatrix.postTranslate(dx, dy)
                                binding.frozenFrameView.imageMatrix = frozenFrameMatrix
                            }
                            lastX = event.getX(idx); lastY = event.getY(idx)
                        }
                    } else isDragging = true
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (!isDragging && !scaleDetector.isInProgress) {
                        // This was a tap — check if it hit a barcode
                        handleScanInputTap(event.x, event.y)
                    }
                    activePointerId = -1
                    true
                }
                android.view.MotionEvent.ACTION_POINTER_UP -> {
                    val pi = event.actionIndex
                    if (event.getPointerId(pi) == activePointerId) {
                        val ni = if (pi == 0) 1 else 0
                        lastX = event.getX(ni); lastY = event.getY(ni)
                        activePointerId = event.getPointerId(ni)
                    }
                    isDragging = true
                    true
                }
                else -> true
            }
        }
    }

    private fun handleScanInputTap(viewX: Float, viewY: Float) {
        // Map view coordinates back to bitmap coordinates using inverse matrix
        val inverse = Matrix()
        frozenFrameMatrix.invert(inverse)
        val pts = floatArrayOf(viewX, viewY)
        inverse.mapPoints(pts)
        val bx = pts[0]; val by = pts[1]

        for (i in scanInputBarcodeRects.indices) {
            val rect = scanInputBarcodeRects[i].rect
            val touchRect = RectF(rect.left - 30, rect.top - 50, rect.right + 30, rect.bottom + 30)
            if (touchRect.contains(bx, by)) {
                if (scanInputSelectedIndices.contains(i)) scanInputSelectedIndices.remove(i)
                else scanInputSelectedIndices.add(i)
                redrawScanInputBitmap(scanInputSelectedIndices)
                updateScanInputButtons(scanInputSelectedIndices.size)
                return
            }
        }
    }

    private fun exitScanInput() {
        isScanInputFrozen = false
        scanInputSelectedIndices.clear()
        scanInputBaseBitmap = null
        scanInputBarcodeRects = emptyList()
        binding.overlayView.visibility = View.VISIBLE
        binding.overlayView.clear()
        hideFrozenFrame()
        binding.scanInputActions.visibility = View.GONE
        binding.scanInputButton.alpha = 0.7f
        updateStatusText()
    }

    // ---- Debounce & Freeze Frame ----

    private fun resetDebounce() { matchCount = 0; lossCount = 0 }

    private fun freezeFrame(previewBitmap: Bitmap?, matchRects: List<BarcodeOverlayView.BarcodeRect>) {
        isFrozen = true; pendingMatchRects = matchRects

        if (previewBitmap != null) {
            // Draw match overlays directly onto the bitmap
            val bmp = previewBitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(bmp)
            val bw = bmp.width.toFloat(); val bh = bmp.height.toFloat()
            val sw = lastImageWidth.toFloat(); val sh = lastImageHeight.toFloat()
            val scale = maxOf(bw / sw, bh / sh)
            val ox = (bw - sw * scale) / 2f; val oy = (bh - sh * scale) / 2f

            val strokeP = Paint().apply { color = Color.parseColor("#4CAF50"); style = Paint.Style.STROKE; strokeWidth = 8f; isAntiAlias = true }
            val fillP = Paint().apply { color = Color.parseColor("#554CAF50"); style = Paint.Style.FILL; isAntiAlias = true }
            val textP = Paint().apply { color = Color.parseColor("#4CAF50"); textSize = 36f; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD; setShadowLayer(4f, 2f, 2f, Color.BLACK) }
            val bgP = Paint().apply { color = Color.parseColor("#EE1B5E20"); style = Paint.Style.FILL; isAntiAlias = true }

            for (rect in matchRects) {
                val mr = RectF(
                    rect.rect.left * scale + ox, rect.rect.top * scale + oy,
                    rect.rect.right * scale + ox, rect.rect.bottom * scale + oy
                )
                canvas.drawRoundRect(mr, 12f, 12f, fillP)
                canvas.drawRoundRect(mr, 12f, 12f, strokeP)
                val label = "FOUND: ${rect.value}"
                val tw = textP.measureText(label)
                val tx = (mr.centerX() - tw / 2f).coerceAtLeast(8f)
                val ty = (mr.top - 14f).coerceAtLeast(textP.textSize + 8f)
                canvas.drawRoundRect(RectF(tx - 10f, ty - textP.textSize - 6f, tx + tw + 10f, ty + 8f), 8f, 8f, bgP)
                canvas.drawText(label, tx, ty, textP)
            }

            showFrozenBitmap(bmp)
        }

        binding.overlayView.clear()
        binding.freezeActions.visibility = View.VISIBLE
        binding.freezeActions.bringToFront()
        binding.statusText.bringToFront()
        val foundLabel = matchRects.joinToString(", ") { it.value }
        binding.statusText.text = "Match: $foundLabel"
        binding.statusText.setBackgroundResource(R.drawable.status_background)
        triggerAlerts()
    }

    private fun dismissFreezeFrame() {
        isFrozen = false; resetDebounce()
        hideFrozenFrame()
        binding.freezeActions.visibility = View.GONE
        binding.overlayView.clear()
        refreshSearchListUI()
        updateStatusText()
    }

    // ---- Camera Controls ----

    private val zoomHideHandler = Handler(Looper.getMainLooper())
    private val zoomHideRunnable = Runnable {
        binding.zoomText.animate().alpha(0f).setDuration(300).withEndAction {
            binding.zoomText.visibility = View.GONE
        }.start()
    }

    private fun setupCameraControls() {
        binding.torchButton.setOnClickListener {
            camera?.let { cam ->
                if (cam.cameraInfo.hasFlashUnit()) {
                    isTorchOn = !isTorchOn; cam.cameraControl.enableTorch(isTorchOn)
                    binding.torchButton.setImageResource(if (isTorchOn) R.drawable.ic_flashlight_on else R.drawable.ic_flashlight_off)
                } else Toast.makeText(this, "No flashlight available", Toast.LENGTH_SHORT).show()
            }
        }

        val scaleGestureDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    camera?.let { cam ->
                        val current = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                        val max = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 10f
                        cam.cameraControl.setZoomRatio((current * detector.scaleFactor).coerceIn(1f, max))
                        showZoomIndicator(cam.cameraInfo.zoomState.value?.zoomRatio ?: current)
                    }; return true
                }
            }
        )

        binding.previewView.setOnTouchListener { view, event ->
            scaleGestureDetector.onTouchEvent(event)
            if (event.action == android.view.MotionEvent.ACTION_UP && !scaleGestureDetector.isInProgress) {
                val point = binding.previewView.meteringPointFactory.createPoint(event.x, event.y)
                camera?.cameraControl?.startFocusAndMetering(
                    FocusMeteringAction.Builder(point).setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS).build()
                )
            }; view.performClick(); true
        }
    }

    private fun showZoomIndicator(zoom: Float) {
        binding.zoomText.text = String.format("%.1fx", zoom)
        binding.zoomText.visibility = View.VISIBLE; binding.zoomText.alpha = 1f
        zoomHideHandler.removeCallbacks(zoomHideRunnable)
        zoomHideHandler.postDelayed(zoomHideRunnable, 1500)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }
            @Suppress("DEPRECATION")
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1920, 1080))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also {
                    it.setAnalyzer(cameraExecutor, BarcodeAnalyzer(getSelectedBarcodeFormats()) { barcodes, w, h ->
                        runOnUiThread { if (!isFrozen && !isScanInputFrozen) handleDetectedBarcodes(barcodes, w, h) }
                    })
                }
            try {
                cameraProvider?.unbindAll()
                camera = cameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) { Toast.makeText(this, "Failed to start camera: ${e.message}", Toast.LENGTH_LONG).show() }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun restartCameraWithFormats() { cameraProvider?.unbindAll(); startCamera() }

    private val formatMap = linkedMapOf(
        "CODE_39" to Barcode.FORMAT_CODE_39, "CODE_128" to Barcode.FORMAT_CODE_128,
        "EAN_13" to Barcode.FORMAT_EAN_13, "EAN_8" to Barcode.FORMAT_EAN_8,
        "UPC_A" to Barcode.FORMAT_UPC_A, "UPC_E" to Barcode.FORMAT_UPC_E,
        "QR_CODE" to Barcode.FORMAT_QR_CODE, "DATA_MATRIX" to Barcode.FORMAT_DATA_MATRIX,
        "PDF_417" to Barcode.FORMAT_PDF417, "AZTEC" to Barcode.FORMAT_AZTEC
    )

    private fun getSelectedBarcodeFormats(): IntArray {
        val f = formatMap.filter { it.key in enabledFormats }.values.toList()
        return if (f.isEmpty()) formatMap.values.toIntArray() else f.toIntArray()
    }

    // ---- Frame Processing ----

    private fun handleDetectedBarcodes(
        detectedBarcodes: List<BarcodeAnalyzer.DetectedBarcode>,
        imageWidth: Int, imageHeight: Int
    ) {
        lastDetectedBarcodes = detectedBarcodes
        lastImageWidth = imageWidth; lastImageHeight = imageHeight

        binding.overlayView.setSourceDimensions(imageWidth, imageHeight)

        val unfoundListItems = searchList.filter { it.active && !it.found }
        val hasListTargets = unfoundListItems.isNotEmpty()
        val hasSingleTarget = !isMultiMode && targetBarcode.isNotEmpty()

        if (!hasSingleTarget && !hasListTargets) {
            resetDebounce()
            binding.overlayView.updateBarcodes(detectedBarcodes.map { BarcodeOverlayView.BarcodeRect(it.boundingBox, it.rawValue, false) })
            updateStatusText(scannedCount = detectedBarcodes.size); return
        }

        val searchTerms = mutableListOf<String>()
        if (hasSingleTarget) searchTerms.add(targetBarcode)
        searchTerms.addAll(unfoundListItems.map { it.barcode })

        val rects = detectedBarcodes.map { b ->
            BarcodeOverlayView.BarcodeRect(b.boundingBox, b.rawValue, searchTerms.any { barcodeMatches(b.rawValue, it) })
        }

        val hasMatch = rects.any { it.isTarget }

        if (hasMatch) { matchCount++; lossCount = 0; pendingMatchRects = rects.filter { it.isTarget } }
        else lossCount++

        if (hasMatch && matchCount >= matchThreshold && !isFrozen) {
            freezeFrame(binding.previewView.bitmap, pendingMatchRects); return
        }

        if (lossCount >= lossThreshold) matchCount = 0

        binding.overlayView.updateBarcodes(rects)
        if (hasMatch) {
            binding.statusText.text = "Locking on... ($matchCount/$matchThreshold)"
            binding.statusText.setBackgroundResource(R.drawable.status_background)
        } else {
            updateStatusText(scannedCount = detectedBarcodes.size)
        }
    }

    // ---- Alerts ----

    private fun triggerAlerts() {
        val now = System.currentTimeMillis()
        if (now - lastAlertTimeMs < alertCooldownMs) return; lastAlertTimeMs = now
        binding.overlayView.triggerFoundFlash()
        if (vibrationStrength > 0) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val t = when (vibrationStrength) { 1 -> longArrayOf(0,100,80,100); 2 -> longArrayOf(0,150,100,150,100,150); 3 -> longArrayOf(0,200,100,200,100,300); else -> longArrayOf(0,150,100,150) }
                    val a = when (vibrationStrength) { 1 -> intArrayOf(0,80,0,80); 2 -> intArrayOf(0,180,0,220,0,255); 3 -> intArrayOf(0,200,0,255,0,255); else -> intArrayOf(0,180,0,220) }
                    val effect = VibrationEffect.createWaveform(t, a, -1)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator.vibrate(effect)
                    else @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(effect)
                } else @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(longArrayOf(0,150,100,150,100,150), -1)
            } catch (_: Exception) {}
        }
        if (alertVolume > 0) { try { toneGenerator?.startTone(alertToneType, 500) } catch (_: Exception) {} }
    }

    // ---- Status ----

    private fun updateStatusText(scannedCount: Int = 0) {
        binding.statusText.setBackgroundResource(R.drawable.status_background)
        val active = searchList.filter { it.active }
        val hasList = active.isNotEmpty()
        val found = active.count { it.found }
        val listSuffix = if (hasList) " | List: $found/${active.size}" else ""

        binding.statusText.text = when {
            isMultiMode && !hasList -> "Enter barcodes to add to search list"
            isMultiMode && scannedCount > 0 -> "List: $found/${active.size} found ($scannedCount visible)"
            isMultiMode -> "List: $found/${active.size} found \u2014 point at barcodes"
            targetBarcode.isEmpty() && scannedCount > 0 -> "Scanning... ($scannedCount visible)$listSuffix"
            targetBarcode.isEmpty() -> "Enter a barcode to search$listSuffix"
            scannedCount > 0 -> "Searching: $targetBarcode ($scannedCount visible)$listSuffix"
            else -> "Searching: $targetBarcode \u2014 point at barcodes$listSuffix"
        }
    }

    // ---- History ----

    private fun addToHistory(barcode: String) {
        scanHistory.add(0, ScanHistoryEntry(barcode, System.currentTimeMillis(), false)); saveHistory()
    }

    private fun showHistoryDialog() {
        if (scanHistory.isEmpty()) { Toast.makeText(this, "No search history yet", Toast.LENGTH_SHORT).show(); return }
        val fmt = SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.US)
        val items = scanHistory.map { "${it.barcode}\n${if (it.found) "\u2713 Found" else "\u2717 Not found"}  |  ${fmt.format(Date(it.timestamp))}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Search History")
            .setItems(items) { _, which ->
                val e = scanHistory[which]
                if (isMultiMode) addToSearchList(e.barcode)
                else { binding.searchInput.setText(e.barcode); addToHistory(e.barcode) }
                hideKeyboard()
            }
            .setNeutralButton("Settings") { _, _ -> showSettingsDialog() }
            .setNegativeButton("Clear History") { _, _ -> scanHistory.clear(); saveHistory(); Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show() }
            .setPositiveButton("Close", null).show()
    }

    // ---- Settings ----

    private fun showSettingsDialog() {
        val fmtNames = arrayOf("Code 39","Code 128","EAN-13","EAN-8","UPC-A","UPC-E","QR Code","Data Matrix","PDF417","Aztec")
        val fmtKeys = arrayOf("CODE_39","CODE_128","EAN_13","EAN_8","UPC_A","UPC_E","QR_CODE","DATA_MATRIX","PDF_417","AZTEC")
        val checked = fmtKeys.map { it in enabledFormats }.toBooleanArray()
        val volOpts = arrayOf("Off","Low","Medium","High"); val vibOpts = arrayOf("Off","Light","Medium","Strong")
        val toneOpts = arrayOf("Beep","Alert","Confirmation","Alarm")
        val stripDisp = if (stripChars.isEmpty()) "None" else stripChars.toList().joinToString("  ") { "\"$it\"" }

        AlertDialog.Builder(this).setTitle("Settings")
            .setItems(arrayOf("Barcode Formats...","Ignore Prefix/Suffix: $stripDisp","Alert Volume: ${volOpts[alertVolume/34]}","Vibration: ${vibOpts[vibrationStrength]}","Alert Sound: ${toneOpts[getToneIndex()]}")) { _, w ->
                when (w) { 0 -> showFormatFilter(fmtNames, fmtKeys, checked); 1 -> showStripChars(); 2 -> showVolume(volOpts); 3 -> showVibration(vibOpts); 4 -> showTone(toneOpts) }
            }.setPositiveButton("Close", null).show()
    }

    private fun showStripChars() {
        val input = android.widget.EditText(this).apply { setText(stripChars); hint = "e.g. *+"; setPadding(48,32,48,16); inputType = android.text.InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this).setTitle("Ignore Prefix/Suffix Characters")
            .setMessage("Characters stripped from start/end of scanned barcodes when matching.\n\nIf your search includes the character, it won't be stripped.")
            .setView(input).setPositiveButton("Save") { _, _ -> stripChars = input.text.toString(); saveSettings() }
            .setNeutralButton("Reset") { _, _ -> stripChars = "*+"; saveSettings() }.setNegativeButton("Cancel", null).show()
    }

    private fun showFormatFilter(n: Array<String>, k: Array<String>, c: BooleanArray) {
        AlertDialog.Builder(this).setTitle("Barcode Formats")
            .setMultiChoiceItems(n, c) { _, w, ch -> if (ch) enabledFormats.add(k[w]) else enabledFormats.remove(k[w]) }
            .setPositiveButton("Apply") { _, _ -> saveSettings(); restartCameraWithFormats() }.setNegativeButton("Cancel", null).show()
    }

    private fun showVolume(o: Array<String>) {
        AlertDialog.Builder(this).setTitle("Alert Volume").setSingleChoiceItems(o, alertVolume/34) { d, w ->
            alertVolume = when(w) { 0->0;1->33;2->66;else->100 }; recreateToneGenerator(); saveSettings(); d.dismiss()
        }.show()
    }

    private fun showVibration(o: Array<String>) {
        AlertDialog.Builder(this).setTitle("Vibration Strength").setSingleChoiceItems(o, vibrationStrength) { d, w -> vibrationStrength = w; saveSettings(); d.dismiss() }.show()
    }

    private fun showTone(o: Array<String>) {
        AlertDialog.Builder(this).setTitle("Alert Sound").setSingleChoiceItems(o, getToneIndex()) { d, w ->
            alertToneType = when(w) { 0->ToneGenerator.TONE_PROP_BEEP;1->ToneGenerator.TONE_PROP_ACK;2->ToneGenerator.TONE_PROP_PROMPT;else->ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD }
            saveSettings(); try { toneGenerator?.startTone(alertToneType, 300) } catch (_: Exception) {}; d.dismiss()
        }.show()
    }

    private fun getToneIndex() = when(alertToneType) { ToneGenerator.TONE_PROP_BEEP->0;ToneGenerator.TONE_PROP_ACK->1;ToneGenerator.TONE_PROP_PROMPT->2;else->3 }

    // ---- Persistence ----

    private fun saveSettings() { prefs.edit().putStringSet("enabled_formats", enabledFormats).putInt("alert_volume", alertVolume).putInt("vibration_strength", vibrationStrength).putInt("alert_tone_type", alertToneType).putString("strip_chars", stripChars).apply() }
    private fun loadSettings() { prefs.getStringSet("enabled_formats", null)?.let { enabledFormats = it.toMutableSet() }; alertVolume = prefs.getInt("alert_volume", 100); vibrationStrength = prefs.getInt("vibration_strength", 2); alertToneType = prefs.getInt("alert_tone_type", ToneGenerator.TONE_PROP_BEEP); stripChars = prefs.getString("strip_chars", "*+") ?: "*+" }

    private fun saveHistory() { val a = JSONArray(); for (e in scanHistory) a.put(JSONObject().apply { put("barcode",e.barcode);put("timestamp",e.timestamp);put("found",e.found) }); prefs.edit().putString("scan_history", a.toString()).apply() }
    private fun loadHistory() { val j = prefs.getString("scan_history", null) ?: return; try { val a = JSONArray(j); scanHistory.clear(); for (i in 0 until a.length()) { val o = a.getJSONObject(i); scanHistory.add(ScanHistoryEntry(o.getString("barcode"), o.getLong("timestamp"), o.getBoolean("found"))) } } catch (_: Exception) {} }

    private fun saveSearchList() { val a = JSONArray(); for (i in searchList) a.put(JSONObject().apply { put("barcode",i.barcode);put("addedAt",i.addedAt);put("found",i.found);put("active",i.active) }); prefs.edit().putString("batch_list", a.toString()).apply() }
    private fun loadSearchList() { val j = prefs.getString("batch_list", null) ?: return; try { val a = JSONArray(j); searchList.clear(); for (i in 0 until a.length()) { val o = a.getJSONObject(i); searchList.add(SearchListItem(o.getString("barcode"), o.getLong("addedAt"), o.getBoolean("found"), o.getBoolean("active"))) } } catch (_: Exception) {} }

    private fun hideKeyboard() { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(binding.searchInput.windowToken, 0) }

    override fun onDestroy() { super.onDestroy(); cameraExecutor.shutdown(); toneGenerator?.release() }
}
