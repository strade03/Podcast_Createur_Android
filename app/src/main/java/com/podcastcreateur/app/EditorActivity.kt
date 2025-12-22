package com.podcastcreateur.app

import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.podcastcreateur.app.databinding.ActivityEditorBinding

import com.linc.amplituda.Amplituda
import com.linc.amplituda.Compress

import kotlinx.coroutines.*
import java.io.File
import kotlin.math.abs

class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var currentFile: File
    
    private var sampleRate = 44100
    private var totalDurationMs = 0L

    private var mediaPlayer: MediaPlayer? = null
    private var playbackJob: Job? = null
    
    private var currentZoom = 1.0f 
    private lateinit var amplituda: Amplituda

    private val pendingCuts = ArrayList<Pair<Long, Long>>() 
    
    // CORRECTION SYNCHRO : On utilise un Double pour la précision
    // C'est le nombre de millisecondes que représente 1 pixel/point de l'onde
    private var msPerPoint: Double = 20.0 

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        amplituda = Amplituda(this)

        val path = intent.getStringExtra("FILE_PATH")
        if (path == null) { finish(); return }
        currentFile = File(path)
        
        binding.txtFilename.text = currentFile.name.replace(Regex("^\\d{3}_"), "")
        
        loadWaveformFast()

        binding.waveformView.onPositionChanged = { index -> updateCurrentTimeDisplay(index) }

        binding.btnPlay.setOnClickListener { 
            if(mediaPlayer?.isPlaying == true) stopAudio() else playAudio() 
        }
        
        binding.btnCut.setOnClickListener { performVirtualCut() }
        binding.btnNormalize.setOnClickListener { normalizeSelectionStreaming() }
        binding.btnSave.setOnClickListener { saveChangesAndExit() }
        
        binding.btnZoomIn.setOnClickListener { applyZoom(currentZoom * 1.5f) }
        binding.btnZoomOut.setOnClickListener { applyZoom(currentZoom / 1.5f) }
        
        binding.btnReRecord.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Refaire ?").setPositiveButton("Oui") { _, _ ->
                stopAudio()
                val regex = Regex("^(\\d{3}_)(.*)\\.(.*)$")
                val match = regex.find(currentFile.name)
                if (match != null) {
                    val (prefix, name, _) = match.destructured
                    val intent = Intent(this, RecorderActivity::class.java)
                    intent.putExtra("PROJECT_PATH", currentFile.parent)
                    intent.putExtra("CHRONICLE_NAME", name)
                    intent.putExtra("CHRONICLE_PREFIX", prefix)
                    startActivity(intent)
                    finish()
                }
            }.setNegativeButton("Non", null).show()
        }
    }

    private fun loadWaveformFast() {
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch(Dispatchers.IO) {
            val meta = AudioHelper.getAudioMetadata(currentFile)
            if (meta != null) {
                sampleRate = meta.sampleRate
                totalDurationMs = meta.duration
            }
            
            // On garde l'estimation pour la demande de compression, 
            // mais on ne s'en sert plus pour la synchro.
            val durationSec = (totalDurationMs / 1000).coerceAtLeast(1)
            val requestPps = when {
                durationSec < 60 -> 400
                durationSec < 300 -> 200
                durationSec < 900 -> 100
                else -> 50 
            }

            amplituda.processAudio(
                currentFile.absolutePath, 
                Compress.withParams(Compress.AVERAGE, requestPps) 
            ).get({ result ->
                val amplitudes = result.amplitudesAsList()
                
                // --- CORRECTION SYNCHRO CRITIQUE ---
                // On recalcule le ratio EXACT basé sur ce qu'Amplituda a VRAIMENT renvoyé
                if (amplitudes.isNotEmpty() && totalDurationMs > 0) {
                    msPerPoint = totalDurationMs.toDouble() / amplitudes.size.toDouble()
                }

                val maxVal = amplitudes.maxOrNull() ?: 1
                val floats = FloatArray(amplitudes.size)
                for (i in amplitudes.indices) {
                    floats[i] = amplitudes[i].toFloat() / maxVal.toFloat() 
                }

                runOnUiThread {
                    binding.txtDuration.text = formatTime(totalDurationMs)
                    binding.waveformView.initialize(floats.size.toLong())
                    binding.waveformView.appendData(floats)
                    
                    binding.scroller.post {
                        val screenWidth = binding.scroller.width
                        if (screenWidth > 0 && floats.isNotEmpty()) {
                            val fitZoom = screenWidth.toFloat() / floats.size.toFloat()
                            applyZoom(fitZoom.coerceAtLeast(0.5f))
                        }
                    }
                    binding.progressBar.visibility = View.GONE
                }
            }, { error ->
                error.printStackTrace()
                runOnUiThread { 
                    Toast.makeText(this@EditorActivity, "Erreur chargement", Toast.LENGTH_SHORT).show()
                    binding.progressBar.visibility = View.GONE
                }
            })
        }
    }

    private fun performVirtualCut() {
        val startIdx = binding.waveformView.selectionStart
        val endIdx = binding.waveformView.selectionEnd
        if (startIdx < 0 || endIdx <= startIdx) return

        stopAudio()
        
        // Calcul avec le ratio précis
        val visualStartMs = (startIdx * msPerPoint).toLong()
        val visualEndMs = (endIdx * msPerPoint).toLong()
        val durationCut = visualEndMs - visualStartMs
        
        val realStartMs = mapVisualToRealTime(visualStartMs)
        val realEndMs = realStartMs + durationCut
        
        pendingCuts.add(Pair(realStartMs, realEndMs))
        binding.waveformView.deleteRange(startIdx, endIdx)
        
        // Recalculer l'affichage du temps total (virtuel)
        // On soustrait la durée de toutes les coupes
        var totalCutMs = 0L
        pendingCuts.forEach { totalCutMs += (it.second - it.first) }
        val displayDuration = totalDurationMs - totalCutMs
        
        binding.txtDuration.text = formatTime(displayDuration)
        
        Toast.makeText(this, "Coupe (en attente de sauvegarde)", Toast.LENGTH_SHORT).show()
    }
    
    private fun mapVisualToRealTime(visualMs: Long): Long {
        var realMs = visualMs
        val sortedCuts = pendingCuts.sortedBy { it.first }
        for (cut in sortedCuts) {
            // Si la coupe est avant le point que je cherche dans le référentiel réel
            if (cut.first < realMs) {
                 // Attention : realMs avance, donc on peut "dépasser" d'autres coupes
                 // Logique simplifiée : on ajoute la durée de la coupe
                 realMs += (cut.second - cut.first)
            }
        }
        return realMs
    }

    private fun saveChangesAndExit() {
        if (pendingCuts.isEmpty()) { finish(); return }
        stopAudio()
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch(Dispatchers.IO) {
            val tmpFile = File(currentFile.parent, "tmp_save_final.m4a")
            val samplesCuts = ArrayList<Pair<Long, Long>>()
            val meta = AudioHelper.getAudioMetadata(currentFile) ?: return@launch
            
            val samplesPerMs = meta.sampleRate / 1000.0
            for (cut in pendingCuts) {
                val sStart = (cut.first * samplesPerMs).toLong()
                val sEnd = (cut.second * samplesPerMs).toLong()
                samplesCuts.add(Pair(sStart, sEnd))
            }
            
            val success = AudioHelper.saveWithCuts(currentFile, tmpFile, samplesCuts)
            withContext(Dispatchers.Main) {
                if (success) {
                    currentFile.delete()
                    tmpFile.renameTo(currentFile)
                    Toast.makeText(this@EditorActivity, "Enregistré !", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this@EditorActivity, "Erreur sauvegarde", Toast.LENGTH_SHORT).show()
                    binding.progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun normalizeSelectionStreaming() {
        stopAudio()
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val tmpFile = File(currentFile.parent, "tmp_norm.m4a")
            val success = AudioHelper.normalizeAudio(currentFile, tmpFile, 0, totalDurationMs, sampleRate, 0.98f) {}
            withContext(Dispatchers.Main) {
                if(success) {
                    currentFile.delete()
                    tmpFile.renameTo(currentFile)
                    pendingCuts.clear() 
                    binding.waveformView.clearData()
                    loadWaveformFast() 
                    Toast.makeText(this@EditorActivity, "Normalisé", Toast.LENGTH_SHORT).show()
                } else {
                    binding.progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun updateCurrentTimeDisplay(index: Int) {
        val visualMs = (index * msPerPoint).toLong()
        binding.txtCurrentTime.text = formatTime(visualMs)
    }
    
    private fun formatTime(durationMs: Long): String {
        val sec = (durationMs / 1000).toInt()
        val m = sec / 60
        val s = sec % 60
        return String.format("%02d:%02d", m, s)
    }

    private fun applyZoom(newZoom: Float) {
        val clamped = newZoom.coerceIn(0.1f, 50.0f)
        currentZoom = clamped
        binding.waveformView.setZoomLevel(currentZoom)
    }

    private fun playAudio() {
        stopAudio()
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(currentFile.absolutePath)
                prepare()
                
                val startIdx = if(binding.waveformView.selectionStart >= 0) binding.waveformView.selectionStart else binding.waveformView.playheadPos
                val visualStartMs = (startIdx * msPerPoint).toLong()
                val realStartMs = mapVisualToRealTime(visualStartMs)
                
                seekTo(realStartMs.toInt())
                start()
                setOnCompletionListener { stopAudio() }
            }
            binding.btnPlay.setImageResource(R.drawable.ic_stop_read)
            
            playbackJob = lifecycleScope.launch {
                val sortedCuts = pendingCuts.sortedBy { it.first }
                while (mediaPlayer?.isPlaying == true) {
                    val currentRealMs = mediaPlayer?.currentPosition?.toLong() ?: 0L
                    
                    var inCut = false
                    for (cut in sortedCuts) {
                        if (currentRealMs >= cut.first && currentRealMs < cut.second) {
                            mediaPlayer?.seekTo(cut.second.toInt())
                            inCut = true
                            break
                        }
                    }
                    
                    if (!inCut) {
                        // Real -> Visual
                        var visualMs = currentRealMs
                        for (cut in sortedCuts) {
                            if (currentRealMs > cut.second) {
                                visualMs -= (cut.second - cut.first)
                            }
                        }
                        
                        // CORRECTION SYNCHRO : Utilisation de msPerPoint (Double)
                        val currentIdx = (visualMs / msPerPoint).toInt()
                        
                        binding.waveformView.playheadPos = currentIdx
                        binding.waveformView.invalidate()
                        runOnUiThread { updateCurrentTimeDisplay(currentIdx) }
                        autoScroll(currentIdx)
                        
                        if (binding.waveformView.selectionEnd > 0 && currentIdx >= binding.waveformView.selectionEnd) {
                            mediaPlayer?.pause()
                            break
                        }
                    }
                    delay(30)
                }
                if (mediaPlayer?.isPlaying != true) stopAudio()
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun autoScroll(sampleIdx: Int) {
        val px = binding.waveformView.sampleToPixel(sampleIdx)
        val screenCenter = binding.scroller.width / 2
        val target = (px - screenCenter).toInt().coerceAtLeast(0)
        if (abs(binding.scroller.scrollX - target) > 50) {
            binding.scroller.smoothScrollTo(target, 0)
        }
    }

    private fun stopAudio() {
        playbackJob?.cancel()
        mediaPlayer?.release()
        mediaPlayer = null
        binding.btnPlay.setImageResource(R.drawable.ic_play)
    }

    override fun onStop() { super.onStop(); stopAudio() }
}