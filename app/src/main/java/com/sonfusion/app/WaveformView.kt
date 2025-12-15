package com.podcastcreateur.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class WaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var samples: ShortArray = ShortArray(0)
    private var zoomFactor = 1.0f 
    
    private val paint = Paint().apply {
        color = Color.BLUE
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val selectionPaint = Paint().apply {
        color = Color.parseColor("#550000FF")
        style = Paint.Style.FILL
    }
    private val playheadPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 4f
    }

    var selectionStart = -1
    var selectionEnd = -1
    var playheadPos = 0

    // NOUVEAU : Mode de toucher
    private var isSelectionMode = true  // true = sélection, false = pan
    private var initialTouchX = 0f
    private var touchDownTime = 0L
    
    // NOUVEAU : GestureDetector pour détecter les gestes
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            // Long press = activer le mode Pan
            isSelectionMode = false
            performHapticFeedback(HAPTIC_FEEDBACK_ENABLED)
        }
        
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // Simple tap = positionner le pointeur
            if (samples.isEmpty() || width == 0) return false
            val sampleIdx = ((e.x / width) * samples.size).toInt().coerceIn(0, samples.size)
            playheadPos = sampleIdx
            clearSelection()
            invalidate()
            return true
        }
    })

    fun setWaveform(data: ShortArray) {
        samples = data
        requestLayout()
        invalidate()
    }
    
    fun setZoomLevel(factor: Float) {
        zoomFactor = factor
        requestLayout()
        invalidate()
    }

    fun clearSelection() { 
        selectionStart = -1
        selectionEnd = -1
        invalidate() 
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val screenWidth = resources.displayMetrics.widthPixels
        val desiredWidth = (screenWidth * zoomFactor).toInt()
        val finalWidth = resolveSize(desiredWidth, widthMeasureSpec)
        val finalHeight = getDefaultSize(suggestedMinimumHeight, heightMeasureSpec)
        setMeasuredDimension(finalWidth, finalHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (samples.isEmpty() || width <= 0) return

        val w = width.toFloat()
        val h = height.toFloat()
        val centerY = h / 2f
        
        val samplesPerPixel = (samples.size / w).coerceAtLeast(0.1f) 

        for (i in 0 until width) {
            val startIdx = (i * samplesPerPixel).toInt()
            val endIdx = ((i + 1) * samplesPerPixel).toInt().coerceAtMost(samples.size)
            
            var maxVal = 0
            if (startIdx < samples.size) {
                if (startIdx >= endIdx) {
                    maxVal = abs(samples[startIdx].toInt())
                } else {
                    for (j in startIdx until endIdx) {
                        val v = abs(samples[j].toInt())
                        if (v > maxVal) maxVal = v
                    }
                }
            }

            val scaledH = (maxVal / 32767f) * centerY
            canvas.drawLine(i.toFloat(), centerY - scaledH, i.toFloat(), centerY + scaledH, paint)
        }

        // Dessiner la sélection
        if (selectionStart >= 0 && selectionEnd > selectionStart) {
            val x1 = (selectionStart.toFloat() / samples.size) * w
            val x2 = (selectionEnd.toFloat() / samples.size) * w
            canvas.drawRect(x1, 0f, x2, h, selectionPaint)
        }

        // Dessiner le pointeur
        val px = (playheadPos.toFloat() / samples.size) * w
        canvas.drawLine(px, 0f, px, h, playheadPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (samples.isEmpty() || width == 0) return false
        
        // Laisser le GestureDetector traiter d'abord
        gestureDetector.onTouchEvent(event)
        
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchX = event.x
                touchDownTime = System.currentTimeMillis()
                isSelectionMode = true // Par défaut, on commence en mode sélection
                
                parent?.requestDisallowInterceptTouchEvent(true)
                
                val sampleIdx = ((event.x / width) * samples.size).toInt().coerceIn(0, samples.size)
                selectionStart = sampleIdx
                selectionEnd = sampleIdx
                playheadPos = sampleIdx
                invalidate()
            }
            
            MotionEvent.ACTION_MOVE -> {
                val sampleIdx = ((event.x / width) * samples.size).toInt().coerceIn(0, samples.size)
                
                if (isSelectionMode) {
                    // Mode sélection : étendre la sélection
                    selectionEnd = sampleIdx
                    invalidate()
                } else {
                    // Mode Pan : laisser le HorizontalScrollView gérer le défilement
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                
                // Si la sélection est trop petite (tap rapide), la considérer comme un simple tap
                val touchDuration = System.currentTimeMillis() - touchDownTime
                val touchDistance = abs(event.x - initialTouchX)
                
                if (touchDuration < 200 && touchDistance < 10) {
                    // C'était un simple tap, effacer la sélection
                    val sampleIdx = ((event.x / width) * samples.size).toInt().coerceIn(0, samples.size)
                    playheadPos = sampleIdx
                    clearSelection()
                } else if (selectionStart > selectionEnd) {
                    // Inverser si nécessaire
                    val temp = selectionStart
                    selectionStart = selectionEnd
                    selectionEnd = temp
                }
                
                isSelectionMode = true
                performClick()
            }
        }
        return true
    }
    
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}