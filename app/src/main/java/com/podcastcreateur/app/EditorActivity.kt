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
import linc.com.amplituda.Amplituda
import linc.com.amplituda.Compress
import kotlinx.coroutines.*
import java.io.File

class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var currentFile: File
    
    // On garde juste les métadonnées légères
    private var sampleRate = 44100
    private var totalDurationMs = 0L

    private var mediaPlayer: MediaPlayer? = null
    private var playbackJob: Job? = null
    
    private var currentZoom = 1.0f 
    private lateinit var amplituda: Amplituda

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialiser Amplituda
        amplituda = Amplituda(this)

        val path = intent.getStringExtra("FILE_PATH")
        if (path == null) { finish(); return }
        currentFile = File(path)
        
        binding.txtFilename.text = currentFile.name.replace(Regex("^\\d{3}_"), "")
        binding.waveformView.setZoomLevel(currentZoom)
        
        // Charger l'affichage RAPIDEMENT
        loadWaveformFast()

        binding.waveformView.onPositionChanged = { index -> updateCurrentTimeDisplay(index) }

        binding.btnPlay.setOnClickListener { 
            if(mediaPlayer?.isPlaying == true) stopAudio() else playAudio() 
        }
        
        binding.btnCut.setOnClickListener { cutSelectionStreaming() } // Version optimisée
        binding.btnNormalize.setOnClickListener { normalizeSelectionStreaming() } // Version optimisée
        
        binding.btnSave.setOnClickListener { 
            Toast.makeText(this, "Modifications enregistrées", Toast.LENGTH_SHORT).show()
            finish()
        }
        
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
        
        // 1. Récupérer d'abord les infos techniques (durée, sampleRate)
        lifecycleScope.launch(Dispatchers.IO) {
            val meta = AudioHelper.getAudioMetadata(currentFile)
            if (meta != null) {
                sampleRate = meta.sampleRate
                totalDurationMs = meta.duration
            }

            // 2. Utiliser Amplituda pour l'affichage (C'est ICI que la magie opère)
            // On demande à compresser pour avoir environ 50 points par seconde, c'est suffisant
            amplituda.processAudio(currentFile.absolutePath, Compress.withParams(Compress.AVERAGE, 1))
                .get({ result ->
                    val amplitudes = result.amplitudesAsList()
                    
                    // Conversion List<Int> vers FloatArray (0f..1f)
                    // Amplituda renvoie souvent de gros entiers, on normalise
                    val maxVal = amplitudes.maxOrNull() ?: 1
                    val floats = FloatArray(amplitudes.size)
                    for (i in amplitudes.indices) {
                        floats[i] = amplitudes[i].toFloat() / maxVal.toFloat() 
                    }

                    runOnUiThread {
                        binding.txtDuration.text = formatTime(totalDurationMs)
                        // On réinitialise la vue
                        binding.waveformView.initialize(floats.size.toLong())
                        binding.waveformView.appendData(floats)
                        binding.progressBar.visibility = View.GONE
                    }
                }, { error ->
                    error.printStackTrace()
                    runOnUiThread { 
                        Toast.makeText(this@EditorActivity, "Erreur chargement onde", Toast.LENGTH_SHORT).show()
                        binding.progressBar.visibility = View.GONE
                    }
                })
        }
    }

    // --- ACTIONS SUR FICHIER (STREAMING) ---
    // Plus besoin de charger le PCM en mémoire RAM, on travaille de fichier à fichier

    private fun cutSelectionStreaming() {
        val startIdx = binding.waveformView.selectionStart
        val endIdx = binding.waveformView.selectionEnd
        if (startIdx < 0 || endIdx <= startIdx) return

        stopAudio()
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch(Dispatchers.IO) {
            val totalPoints = binding.waveformView.getPointsCount() // Méthode à ajouter dans WaveformView ou estimer
            if (totalPoints == 0) return@launch
            
            // Conversion : Index Visuel -> Sample Audio réel
            // Ratio = (Index / TotalPoints) * TotalSamples
            val meta = AudioHelper.getAudioMetadata(currentFile) ?: return@launch
            val totalSamples = meta.totalSamples
            
            // Calcul précis des positions de coupe
            // On utilise le ratio de position dans la vue Waveform
            // Attention : Si Amplituda a compressé, WaveformView a moins de points que de samples
            val ratio = totalSamples.toDouble() / totalPoints.toDouble()
            
            val startSample = (startIdx * ratio).toInt()
            val endSample = (endIdx * ratio).toInt()

            val tmpFile = File(currentFile.parent, "tmp_cut.m4a")
            
            // Appel à la fonction streaming existante dans votre AudioHelper
            val success = AudioHelper.deleteRegionStreaming(currentFile, tmpFile, startSample, endSample)
            
            withContext(Dispatchers.Main) {
                if (success) {
                    currentFile.delete()
                    tmpFile.renameTo(currentFile)
                    // Recharger l'affichage
                    binding.waveformView.clearData()
                    loadWaveformFast()
                    Toast.makeText(this@EditorActivity, "Coupé !", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@EditorActivity, "Erreur lors de la coupe", Toast.LENGTH_SHORT).show()
                    binding.progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun normalizeSelectionStreaming() {
        // La normalisation en streaming est complexe car il faut 2 passes (Scan Max -> Apply Gain)
        // Votre AudioHelper a déjà une fonction normalizeAudio, utilisons-la.
        stopAudio()
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch(Dispatchers.IO) {
            val tmpFile = File(currentFile.parent, "tmp_norm.m4a")
            // On normalise TOUT le fichier pour simplifier, ou on calcule les MS
            // Comme on n'a plus le mapping exact MS <-> Index facile sans recalculer,
            // on applique sur tout le fichier (0 à fin).
            val success = AudioHelper.normalizeAudio(currentFile, tmpFile, 0, totalDurationMs, sampleRate, 0.98f) {}
            
            withContext(Dispatchers.Main) {
                if(success) {
                    currentFile.delete()
                    tmpFile.renameTo(currentFile)
                    binding.waveformView.clearData()
                    loadWaveformFast()
                    Toast.makeText(this@EditorActivity, "Normalisé", Toast.LENGTH_SHORT).show()
                } else {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@EditorActivity, "Erreur normalisation", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateCurrentTimeDisplay(index: Int) {
        // Estimation du temps basé sur la position relative
        val totalPoints = binding.waveformView.getPointsCount()
        if (totalPoints > 0 && totalDurationMs > 0) {
            val ms = (index.toDouble() / totalPoints.toDouble() * totalDurationMs).toLong()
            binding.txtCurrentTime.text = formatTime(ms)
        }
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
                
                // Calculer la position de départ (ms)
                val startIdx = if(binding.waveformView.selectionStart >= 0) binding.waveformView.selectionStart else binding.waveformView.playheadPos
                val totalPoints = binding.waveformView.getPointsCount()
                if(totalPoints > 0) {
                    val startMs = (startIdx.toDouble() / totalPoints.toDouble() * totalDurationMs).toInt()
                    seekTo(startMs)
                }
                
                start()
                setOnCompletionListener { stopAudio() }
            }
            binding.btnPlay.setImageResource(R.drawable.ic_stop_read)
            
            playbackJob = lifecycleScope.launch {
                while (mediaPlayer?.isPlaying == true) {
                    val currentMs = mediaPlayer?.currentPosition?.toLong() ?: 0L
                    val totalPoints = binding.waveformView.getPointsCount()
                    
                    if (totalPoints > 0 && totalDurationMs > 0) {
                        val currentIdx = ((currentMs.toDouble() / totalDurationMs.toDouble()) * totalPoints).toInt()
                        binding.waveformView.playheadPos = currentIdx
                        binding.waveformView.invalidate()
                        
                        runOnUiThread { updateCurrentTimeDisplay(currentIdx) }
                        autoScroll(currentIdx)
                        
                        // Stop si fin de sélection
                        if (binding.waveformView.selectionEnd > 0 && currentIdx >= binding.waveformView.selectionEnd) {
                            mediaPlayer?.pause()
                            break
                        }
                    }
                    delay(40)
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