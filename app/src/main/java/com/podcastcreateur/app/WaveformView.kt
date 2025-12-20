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

    // On stocke les points (Peaks) au lieu de tout recalculer
    private val points = ArrayList<Float>()
    
    // Total estimé (pour définir la largeur du scroll avant que tout soit chargé)
    private var totalSamplesEstimate = 0L
    
    // Facteur fixe : 1 point = 882 samples (pour 44.1kHz -> 50pts/sec)
    private val samplesPerPoint = 882
    
    // Zoom : Nombre de pixels par Point.
    // Zoom 1.0 = 1 pixel par point (très compressé). 
    // Zoom 10.0 = 10 pixels par point (large).
    private var zoomFactor = 1.0f 

    var selectionStart = -1
    var selectionEnd = -1
    var playheadPos = 0

    private val paint = Paint().apply {
        color = Color.parseColor("#3F51B5") // Bleu Indigo
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = false // Plus rapide
    }
    
    private val centerLinePaint = Paint().apply {
        color = Color.LTGRAY
        strokeWidth = 1f
    }
    
    private val selectionPaint = Paint().apply {
        color = Color.parseColor("#44FFEB3B") // Jaune semi-transparent
        style = Paint.Style.FILL
    }
    
    private val playheadPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 3f
    }

    private var isSelectionMode = true
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            playheadPos = pixelToSample(e.x)
            selectionStart = -1; selectionEnd = -1
            invalidate()
            return true
        }
    })

    fun initialize(totalSamples: Long) {
        this.totalSamplesEstimate = totalSamples
        clearData()
    }
    
    fun clearData() {
        points.clear()
        selectionStart = -1
        selectionEnd = -1
        playheadPos = 0
        requestLayout()
        invalidate()
    }

    fun appendData(newPoints: FloatArray) {
        for(p in newPoints) points.add(p)
        requestLayout() // Recalculer la largeur
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

    // Calcul de la largeur totale de la vue
    // Elle dépend du Zoom et du nombre total d'échantillons (estimé ou réel)
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalPoints = if (points.size > 0 && points.size * samplesPerPoint > totalSamplesEstimate) {
            points.size.toLong()
        } else {
            totalSamplesEstimate / samplesPerPoint
        }
        
        val contentWidth = (totalPoints * zoomFactor).toInt()
        
        val finalWidth = resolveSize(contentWidth, widthMeasureSpec)
        val finalHeight = getDefaultSize(suggestedMinimumHeight, heightMeasureSpec)
        setMeasuredDimension(contentWidth.coerceAtLeast(finalWidth), finalHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val h = height.toFloat()
        val centerY = h / 2f
        
        // Ligne centrale
        canvas.drawLine(0f, centerY, width.toFloat(), centerY, centerLinePaint)

        // Optimisation : Ne dessiner que ce qui est visible à l'écran
        // Le Parent est un HorizontalScrollView, mais on peut estimer la zone visible
        // Ici on dessine tout car c'est une View standard, le GPU clippera.
        // Pour ultra-optimisation, faudrait passer clipBounds.
        
        // On dessine des lignes verticales centrées (Peak)
        // ZoomFactor détermine l'espacement entre chaque barre
        
        // val barWidth = (zoomFactor * 0.8f).coerceAtLeast(1f) // (Inutilisé, sert si drawRect)
        
        for (i in points.indices) {
            val x = i * zoomFactor
            
            // Peak value (0.0 à 1.0)
            val valPeak = points[i] 
            
            // Hauteur de la barre
            val barHeight = valPeak * centerY * 1.8f // 1.8 pour laisser un peu de marge
            
            canvas.drawLine(x, centerY - barHeight, x, centerY + barHeight, paint)
        }

        // Dessin Sélection
        if (selectionStart >= 0 && selectionEnd > selectionStart) {
            val x1 = sampleToPixel(selectionStart)
            val x2 = sampleToPixel(selectionEnd)
            canvas.drawRect(x1, 0f, x2, h, selectionPaint)
        }

        // Dessin Playhead
        val px = sampleToPixel(playheadPos)
        canvas.drawLine(px, 0f, px, h, playheadPaint)
    }
    
    fun sampleToPixel(sample: Int): Float {
        val pointIndex = sample / samplesPerPoint
        return pointIndex * zoomFactor
    }

    fun pixelToSample(x: Float): Int {
        val pointIndex = x / zoomFactor
        return (pointIndex * samplesPerPoint).toInt()
    }
    
    fun getCenterSample(scrollX: Int, visibleWidth: Int): Int {
        val centerX = scrollX + (visibleWidth / 2)
        return pixelToSample(centerX.toFloat())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        when(event.action) {
            MotionEvent.ACTION_DOWN -> {
                selectionStart = pixelToSample(event.x)
                selectionEnd = selectionStart
                playheadPos = selectionStart
                isSelectionMode = true
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if(isSelectionMode) {
                    val s = pixelToSample(event.x)
                    selectionEnd = s
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if(selectionStart > selectionEnd) {
                    val t = selectionStart; selectionStart = selectionEnd; selectionEnd = t
                }
                // Si tout petit clic, c'est juste un déplacement curseur
                if(abs(selectionEnd - selectionStart) < samplesPerPoint * 10) {
                    selectionStart = -1; selectionEnd = -1
                }
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