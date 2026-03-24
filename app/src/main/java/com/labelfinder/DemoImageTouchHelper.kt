package com.labelfinder

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Attaches zoom (scroll wheel + pinch) and pan (drag) gestures to a set
 * of views so they move and scale together. Used for the static demo image
 * on emulators.
 */
class DemoImageTouchHelper(
    private val touchTarget: View,
    private vararg val views: View
) {
    var enabled = true

    private var scaleFactor = 1f
    private var translateX = 0f
    private var translateY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    private val scaleDetector = ScaleGestureDetector(
        touchTarget.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(0.5f, 5f)
                applyTransform()
                return true
            }
        }
    )

    @SuppressLint("ClickableViewAccessibility")
    fun attach() {
        // Scroll wheel for zoom
        touchTarget.setOnGenericMotionListener { _, event ->
            if (!enabled) return@setOnGenericMotionListener false
            if (event.action == MotionEvent.ACTION_SCROLL) {
                val delta = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val zoomStep = if (delta > 0) 1.15f else 0.85f
                scaleFactor = (scaleFactor * zoomStep).coerceIn(0.5f, 5f)
                applyTransform()
                true
            } else false
        }

        // Drag to pan + pinch to zoom
        touchTarget.setOnTouchListener { _, event ->
            if (!enabled) return@setOnTouchListener false
            scaleDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activePointerId = event.getPointerId(0)
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress) {
                        val pointerIndex = event.findPointerIndex(activePointerId)
                        if (pointerIndex >= 0) {
                            val x = event.getX(pointerIndex)
                            val y = event.getY(pointerIndex)
                            translateX += x - lastTouchX
                            translateY += y - lastTouchY
                            lastTouchX = x
                            lastTouchY = y
                            applyTransform()
                        }
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val pointerIndex = event.actionIndex
                    val pointerId = event.getPointerId(pointerIndex)
                    if (pointerId == activePointerId) {
                        val newIndex = if (pointerIndex == 0) 1 else 0
                        if (newIndex < event.pointerCount) {
                            lastTouchX = event.getX(newIndex)
                            lastTouchY = event.getY(newIndex)
                            activePointerId = event.getPointerId(newIndex)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                }
            }
            true
        }
    }

    private fun applyTransform() {
        for (view in views) {
            view.scaleX = scaleFactor
            view.scaleY = scaleFactor
            view.translationX = translateX
            view.translationY = translateY
        }
    }
}
