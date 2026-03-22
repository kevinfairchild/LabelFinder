package com.labelfinder

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class BarcodeOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class BarcodeRect(
        val rect: RectF,
        val value: String,
        val isTarget: Boolean
    )

    private var barcodes: List<BarcodeRect> = emptyList()

    // Sticky found state
    private var stickyFoundRects: List<BarcodeRect> = emptyList()
    private var stickyExpireTime: Long = 0
    private val stickyDurationMs = 3000L

    // Screen flash state
    private var flashAlpha = 0
    private var isFlashing = false

    // Selectable mode (for scan input)
    var isSelectableMode = false
    private val selectedIndices = mutableSetOf<Int>()
    var onSelectionChanged: ((selected: List<String>) -> Unit)? = null

    private var sourceWidth: Int = 1
    private var sourceHeight: Int = 1

    private val defaultPaint = Paint().apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true
    }

    private val targetStrokePaint = Paint().apply {
        color = Color.parseColor("#4CAF50"); style = Paint.Style.STROKE; strokeWidth = 10f; isAntiAlias = true
    }

    private val targetFillPaint = Paint().apply {
        color = Color.parseColor("#554CAF50"); style = Paint.Style.FILL; isAntiAlias = true
    }

    private val targetGlowPaint = Paint().apply {
        color = Color.parseColor("#4CAF50"); style = Paint.Style.STROKE; strokeWidth = 24f; isAntiAlias = true
        maskFilter = BlurMaskFilter(30f, BlurMaskFilter.Blur.NORMAL)
    }

    private val cornerPaint = Paint().apply {
        color = Color.parseColor("#4CAF50"); style = Paint.Style.STROKE; strokeWidth = 6f; isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE; textSize = 36f; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    private val textBgPaint = Paint().apply {
        color = Color.parseColor("#CC000000"); style = Paint.Style.FILL; isAntiAlias = true
    }

    private val flashBorderPaint = Paint().apply {
        color = Color.parseColor("#4CAF50"); style = Paint.Style.STROKE; strokeWidth = 20f; isAntiAlias = true
    }

    private val stickyLabelPaint = Paint().apply {
        color = Color.parseColor("#4CAF50"); textSize = 44f; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(6f, 2f, 2f, Color.BLACK)
    }

    // Selectable mode paints
    private val selectableStrokePaint = Paint().apply {
        color = Color.parseColor("#90CAF9"); style = Paint.Style.STROKE; strokeWidth = 4f; isAntiAlias = true
    }

    private val selectedStrokePaint = Paint().apply {
        color = Color.parseColor("#4CAF50"); style = Paint.Style.STROKE; strokeWidth = 6f; isAntiAlias = true
    }

    private val selectedFillPaint = Paint().apply {
        color = Color.parseColor("#334CAF50"); style = Paint.Style.FILL; isAntiAlias = true
    }

    private val checkPaint = Paint().apply {
        color = Color.parseColor("#4CAF50"); textSize = 28f; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD
    }

    private var pulseAlpha = 255
    private var pulseDirection = -8

    fun setSourceDimensions(width: Int, height: Int) {
        sourceWidth = width; sourceHeight = height
    }

    fun updateBarcodes(newBarcodes: List<BarcodeRect>) {
        val newTargets = newBarcodes.filter { it.isTarget }
        if (newTargets.isNotEmpty()) {
            stickyFoundRects = newTargets
            stickyExpireTime = System.currentTimeMillis() + stickyDurationMs
        }
        barcodes = newBarcodes
        invalidate()
    }

    fun triggerFoundFlash() {
        flashAlpha = 200; isFlashing = true; invalidate()
    }

    fun clear() {
        barcodes = emptyList(); stickyFoundRects = emptyList(); stickyExpireTime = 0
        selectedIndices.clear(); invalidate()
    }

    fun clearSelection() {
        selectedIndices.clear(); invalidate()
    }

    fun getSelectedValues(): List<String> {
        return selectedIndices.filter { it < barcodes.size }.map { barcodes[it].value }
    }

    private var touchDownIndex = -1

    private fun findBarcodeAt(x: Float, y: Float): Int {
        for (i in barcodes.indices) {
            val mapped = mapRect(barcodes[i].rect)
            // Include the label area above the rect in the touch target
            val touchRect = RectF(mapped.left - 30, mapped.top - 60, mapped.right + 30, mapped.bottom + 30)
            if (touchRect.contains(x, y)) return i
        }
        return -1
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isSelectableMode) return super.onTouchEvent(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchDownIndex = findBarcodeAt(event.x, event.y)
                // Only consume if touching a barcode — let other touches pass through to buttons
                return touchDownIndex >= 0
            }
            MotionEvent.ACTION_UP -> {
                if (touchDownIndex >= 0) {
                    val upIndex = findBarcodeAt(event.x, event.y)
                    if (upIndex == touchDownIndex) {
                        if (selectedIndices.contains(upIndex)) selectedIndices.remove(upIndex)
                        else selectedIndices.add(upIndex)
                        onSelectionChanged?.invoke(getSelectedValues())
                        invalidate()
                        performClick()
                    }
                    touchDownIndex = -1
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (isSelectableMode) {
            drawSelectableMode(canvas)
            return
        }

        val now = System.currentTimeMillis()
        val hasLiveTarget = barcodes.any { it.isTarget }
        val hasStickyTarget = !hasLiveTarget && stickyFoundRects.isNotEmpty() && now < stickyExpireTime

        // Screen border flash
        if (isFlashing && flashAlpha > 0) {
            flashBorderPaint.alpha = flashAlpha
            canvas.drawRect(10f, 10f, width - 10f, height - 10f, flashBorderPaint)
            flashAlpha = (flashAlpha - 6).coerceAtLeast(0)
            if (flashAlpha > 0) postInvalidateDelayed(16) else isFlashing = false
        }

        for (barcode in barcodes) {
            if (!barcode.isTarget) {
                val mapped = mapRect(barcode.rect)
                defaultPaint.alpha = if (hasLiveTarget || hasStickyTarget) 60 else 200
                canvas.drawRoundRect(mapped, 8f, 8f, defaultPaint)
                if (!hasLiveTarget && !hasStickyTarget) drawLabel(canvas, mapped, barcode.value)
            }
        }

        if (hasLiveTarget) {
            for (b in barcodes.filter { it.isTarget }) drawTargetHighlight(canvas, mapRect(b.rect), b.value)
        } else if (hasStickyTarget) {
            val fade = ((stickyExpireTime - now).toFloat() / stickyDurationMs).coerceIn(0f, 1f)
            for (b in stickyFoundRects) drawTargetHighlight(canvas, mapRect(b.rect), b.value, fade)
        }

        if (hasLiveTarget || hasStickyTarget || isFlashing) postInvalidateDelayed(30)
    }

    private fun drawSelectableMode(canvas: Canvas) {
        for (i in barcodes.indices) {
            val mapped = mapRect(barcodes[i].rect)
            val isSelected = i in selectedIndices

            if (isSelected) {
                canvas.drawRoundRect(mapped, 10f, 10f, selectedFillPaint)
                canvas.drawRoundRect(mapped, 10f, 10f, selectedStrokePaint)
            } else {
                canvas.drawRoundRect(mapped, 10f, 10f, selectableStrokePaint)
            }

            // Draw barcode value label
            val labelPaint = Paint(textPaint).apply {
                textSize = 30f
                color = if (isSelected) Color.parseColor("#4CAF50") else Color.WHITE
            }
            val text = if (isSelected) "\u2713 ${barcodes[i].value}" else barcodes[i].value
            val textWidth = labelPaint.measureText(text)
            val textX = (mapped.centerX() - textWidth / 2f).coerceAtLeast(8f)
            val textY = (mapped.top - 10f).coerceAtLeast(labelPaint.textSize + 8f)

            val bgRect = RectF(textX - 8f, textY - labelPaint.textSize - 4f, textX + textWidth + 8f, textY + 6f)
            val bgColor = if (isSelected) Color.parseColor("#DD1B5E20") else Color.parseColor("#CC000000")
            val bgPaint = Paint(textBgPaint).apply { color = bgColor }

            canvas.drawRoundRect(bgRect, 8f, 8f, bgPaint)
            canvas.drawText(text, textX, textY, labelPaint)

            // "Tap to select" hint for unselected
            if (!isSelected) {
                val hintPaint = Paint().apply {
                    color = Color.parseColor("#80FFFFFF"); textSize = 20f; isAntiAlias = true
                }
                val hint = "tap to select"
                val hw = hintPaint.measureText(hint)
                canvas.drawText(hint, mapped.centerX() - hw / 2f, mapped.bottom + 22f, hintPaint)
            }
        }
    }

    private fun drawTargetHighlight(canvas: Canvas, rect: RectF, value: String, alpha: Float = 1f) {
        pulseAlpha += pulseDirection
        if (pulseAlpha <= 80 || pulseAlpha >= 255) pulseDirection = -pulseDirection

        targetGlowPaint.alpha = (pulseAlpha * alpha).toInt()
        canvas.drawRoundRect(rect, 14f, 14f, targetGlowPaint)
        targetFillPaint.alpha = (0x55 * alpha).toInt()
        canvas.drawRoundRect(rect, 14f, 14f, targetFillPaint)
        targetStrokePaint.alpha = (255 * alpha).toInt()
        canvas.drawRoundRect(rect, 14f, 14f, targetStrokePaint)
        drawCornerBrackets(canvas, rect, alpha)
        drawFoundLabel(canvas, rect, value, alpha)
    }

    private fun drawCornerBrackets(canvas: Canvas, rect: RectF, alpha: Float) {
        cornerPaint.alpha = (255 * alpha).toInt()
        val len = 30f.coerceAtMost(rect.width() / 3f)
        canvas.drawLine(rect.left - 4, rect.top, rect.left + len, rect.top, cornerPaint)
        canvas.drawLine(rect.left, rect.top - 4, rect.left, rect.top + len, cornerPaint)
        canvas.drawLine(rect.right + 4, rect.top, rect.right - len, rect.top, cornerPaint)
        canvas.drawLine(rect.right, rect.top - 4, rect.right, rect.top + len, cornerPaint)
        canvas.drawLine(rect.left - 4, rect.bottom, rect.left + len, rect.bottom, cornerPaint)
        canvas.drawLine(rect.left, rect.bottom + 4, rect.left, rect.bottom - len, cornerPaint)
        canvas.drawLine(rect.right + 4, rect.bottom, rect.right - len, rect.bottom, cornerPaint)
        canvas.drawLine(rect.right, rect.bottom + 4, rect.right, rect.bottom - len, cornerPaint)
    }

    private fun drawFoundLabel(canvas: Canvas, rect: RectF, value: String, alpha: Float) {
        val paint = Paint(stickyLabelPaint).apply { this.alpha = (255 * alpha).toInt() }
        val text = "\u2713 FOUND: $value"
        val tw = paint.measureText(text)
        val tx = (rect.centerX() - tw / 2f).coerceAtLeast(8f)
        val ty = (rect.top - 20f).coerceAtLeast(paint.textSize + 16f)
        val bg = RectF(tx - 12f, ty - paint.textSize - 8f, tx + tw + 12f, ty + 12f)
        val bgP = Paint(textBgPaint).apply { color = Color.parseColor("#EE1B5E20"); this.alpha = (0xEE * alpha).toInt() }
        canvas.drawRoundRect(bg, 10f, 10f, bgP)
        canvas.drawText(text, tx, ty, paint)
    }

    private fun drawLabel(canvas: Canvas, rect: RectF, text: String) {
        val paint = Paint(textPaint).apply { textSize = 32f }
        val tw = paint.measureText(text)
        val tx = rect.left; val ty = rect.top - 12f
        val bg = RectF(tx - 8f, ty - paint.textSize - 4f, tx + tw + 8f, ty + 8f)
        canvas.drawRoundRect(bg, 8f, 8f, textBgPaint)
        canvas.drawText(text, tx, ty, paint)
    }

    private fun mapRect(rect: RectF): RectF {
        val vw = width.toFloat(); val vh = height.toFloat()
        val sx = vw / sourceWidth.toFloat(); val sy = vh / sourceHeight.toFloat()
        val s = maxOf(sx, sy)
        val ox = (vw - sourceWidth * s) / 2f; val oy = (vh - sourceHeight * s) / 2f
        return RectF(rect.left * s + ox, rect.top * s + oy, rect.right * s + ox, rect.bottom * s + oy)
    }
}
