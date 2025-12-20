package com.podcastcreateur.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View

/**
 * WAVEFORM VIEW OPTIMISÉE :
 * - Affiche des données downsamplées (FloatArray) au lieu de tous les samples
 * - Gère le mapping entre pixels et samples originaux
 * - Supporte zoom et sélection
 */
class WaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // Données downsamplées pour l'affichage
    private var waveformData: FloatArray = FloatArray(0)
    
    // Nombre total de samples dans le fichier original (pour les calculs de position)
    private var totalSamples = 0
    
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

    // Sélection et lecture en SAMPLES du fichier original (pas en pixels)
    var selectionStart = -1
    var selectionEnd = -1
    var playheadPos = 0

    private var isSelectionMode = true
    private var initialTouchX = 0f
    private var touchDownTime = 0L
    
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            isSelectionMode = false
            performHapticFeedback(HAPTIC_FEEDBACK_ENABLED)
        }
        
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (totalSamples == 0 || width == 0) return false
            val sampleIdx = pixelToSample(e.x)
            playheadPos = sampleIdx
            clearSelection()
            invalidate()
            return true
        }
    })

    /**
     * NOUVELLE FONCTION : Charge les données downsamplées
     */
    fun setWaveformData(data: FloatArray, totalSamplesCount: Int) {
        waveformData = data
        totalSamples = totalSamplesCount
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
        if (waveformData.isEmpty() || width <= 0) return

        val w = width.toFloat()
        val h = height.toFloat()
        val centerY = h / 2f
        
        // Dessiner la waveform downsamplée
        val pixelsPerPoint = w / waveformData.size
        
        for (i in waveformData.indices) {
            val x = i * pixelsPerPoint
            val amplitude = waveformData[i]
            val scaledH = amplitude * centerY
            
            canvas.drawLine(
                x, centerY - scaledH,
                x, centerY + scaledH,
                paint
            )
        }

        // Dessiner la sélection (en samples du fichier original)
        if (selectionStart >= 0 && selectionEnd > selectionStart && totalSamples > 0) {
            val x1 = sampleToPixel(selectionStart)
            val x2 = sampleToPixel(selectionEnd)
            canvas.drawRect(x1, 0f, x2, h, selectionPaint)
        }

        // Dessiner le pointeur de lecture (en samples du fichier original)
        if (totalSamples > 0) {
            val px = sampleToPixel(playheadPos)
            canvas.drawLine(px, 0f, px, h, playheadPaint)
        }
    }

    /**
     * Convertit une position pixel en index de sample du fichier original
     */
    private fun pixelToSample(pixelX: Float): Int {
        if (totalSamples == 0 || width == 0) return 0
        val ratio = pixelX / width
        return (ratio * totalSamples).toInt().coerceIn(0, totalSamples)
    }

    /**
     * Convertit un index de sample en position pixel
     */
    private fun sampleToPixel(sampleIdx: Int): Float {
        if (totalSamples == 0 || width == 0) return 0f
        val ratio = sampleIdx.toFloat() / totalSamples
        return ratio * width
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (totalSamples == 0 || width == 0) return false
        
        gestureDetector.onTouchEvent(event)
        
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchX = event.x
                touchDownTime = System.currentTimeMillis()
                isSelectionMode = true
                
                parent?.requestDisallowInterceptTouchEvent(true)
                
                val sampleIdx = pixelToSample(event.x)
                selectionStart = sampleIdx
                selectionEnd = sampleIdx
                playheadPos = sampleIdx
                invalidate()
            }
            
            MotionEvent.ACTION_MOVE -> {
                val sampleIdx = pixelToSample(event.x)
                
                if (isSelectionMode) {
                    selectionEnd = sampleIdx
                    invalidate()
                } else {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                
                val touchDuration = System.currentTimeMillis() - touchDownTime
                val touchDistance = kotlin.math.abs(event.x - initialTouchX)
                
                if (touchDuration < 200 && touchDistance < 10) {
                    val sampleIdx = pixelToSample(event.x)
                    playheadPos = sampleIdx
                    clearSelection()
                } else if (selectionStart > selectionEnd) {
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