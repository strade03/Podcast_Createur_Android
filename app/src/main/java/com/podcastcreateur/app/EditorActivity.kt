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
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.abs

class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var currentFile: File
    
    private var metadata: AudioMetadata? = null
    private var mediaPlayer: MediaPlayer? = null
    private var playbackJob: Job? = null
    
    private var currentZoom = 1.0f  // Zom initial 

    private var workingPcm: ShortArray? = null
    private var isPcmReady = false
    private var sampleRate = 44100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra("FILE_PATH")
        if (path == null) { finish(); return }
        currentFile = File(path)
        
        binding.txtFilename.text = currentFile.name.replace(Regex("^\\d{3}_"), "")
        binding.waveformView.setZoomLevel(currentZoom)
        
        loadWaveformStreaming()

        binding.waveformView.onPositionChanged = { index -> updateCurrentTimeDisplay(index)}

        binding.btnPlay.setOnClickListener { 
            if(mediaPlayer?.isPlaying == true) stopAudio() else playAudio() 
        }
        
        binding.btnCut.setOnClickListener { cutSelection() }
        binding.btnNormalize.setOnClickListener { normalizeSelection() }
        binding.btnSave.setOnClickListener { 
            Toast.makeText(this, "Sauvegardé", Toast.LENGTH_SHORT).show()
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
        // updateEditButtons(false)        
        // loadEditorData()
    }

    private fun updateEditButtons(enabled: Boolean) {
        isPcmReady = enabled
        val alpha = if (enabled) 1.0f else 0.5f
        binding.btnCut.isEnabled = enabled
        binding.btnCut.alpha = alpha
        binding.btnNormalize.isEnabled = enabled
        binding.btnNormalize.alpha = alpha
        // Le bouton Play et Zoom restent activés
    }

    private fun loadEditorData() {
        // TÂCHE 1 : Affichage rapide (Streaming)
        lifecycleScope.launch(Dispatchers.IO) {
            metadata = AudioHelper.getAudioMetadata(currentFile)
            val meta = metadata ?: return@launch

            withContext(Dispatchers.Main) {
                binding.txtDuration.text = formatTime(meta.duration)
                val estimatedPoints = (meta.duration / 1000) * AudioHelper.POINTS_PER_SECOND
                binding.waveformView.initialize(estimatedPoints)
            }

            AudioHelper.loadWaveformStream(currentFile) { newChunk ->
                runOnUiThread {
                    binding.waveformView.appendData(newChunk)
                }
            }
        }

        // TÂCHE 2 : Chargement du PCM en arrière-plan pour l'édition
        lifecycleScope.launch(Dispatchers.IO) {
            val content = AudioHelper.decodeToPCM(currentFile)
            workingPcm = content.data
            sampleRate = content.sampleRate
            
            withContext(Dispatchers.Main) {
                updateEditButtons(true)
                // Optionnel : On peut faire vibrer légèrement pour dire "Prêt pour l'édition"
                Toast.makeText(this@EditorActivity, "Édition prête", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadWaveformStreaming() {
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch(Dispatchers.IO) {
            metadata = AudioHelper.getAudioMetadata(currentFile)
            val meta = metadata
            
            if (meta == null) {
                withContext(Dispatchers.Main) { finish() }
                return@launch
            }

            withContext(Dispatchers.Main) {
                binding.txtDuration.text = formatTime(meta.duration)
                // On estime le nombre de points : Durée (sec) * 50
                val estimatedPoints = (meta.duration / 1000) * AudioHelper.POINTS_PER_SECOND
                binding.waveformView.initialize(estimatedPoints)
            }

            AudioHelper.loadWaveformStream(currentFile) { newChunk ->
                runOnUiThread {
                    binding.waveformView.appendData(newChunk)
                    if (binding.progressBar.visibility == View.VISIBLE) {
                        binding.progressBar.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun updateCurrentTimeDisplay(index: Int) {
        // Calcul : index * 20ms (car 50 points par seconde)
        val ms = index.toLong() * (1000 / AudioHelper.POINTS_PER_SECOND)
        binding.txtCurrentTime.text = formatTime(ms)
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
        val centerSample = binding.waveformView.getCenterSample(binding.scroller.scrollX, binding.scroller.width)
        binding.waveformView.setZoomLevel(currentZoom)
        binding.waveformView.post {
            val newScrollX = binding.waveformView.sampleToPixel(centerSample) - (binding.scroller.width / 2)
            binding.scroller.scrollTo(newScrollX.toInt().coerceAtLeast(0), 0)
        }
    }

    private fun playAudio() {
        val meta = metadata ?: return
        stopAudio()
        
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(currentFile.absolutePath)
                prepare()
                
                // Conversion Index Point -> MS (1 point = 20ms)
                val startIndex = if(binding.waveformView.selectionStart >= 0) 
                                    binding.waveformView.selectionStart 
                                  else 
                                    binding.waveformView.playheadPos
                
                val startMs = startIndex * (1000 / AudioHelper.POINTS_PER_SECOND)
                
                seekTo(startMs)
                start()
                
                setOnCompletionListener { stopAudio() }
            }
            
            binding.btnPlay.setImageResource(R.drawable.ic_stop_read)
            
            playbackJob = lifecycleScope.launch {
                val endIndex = if(binding.waveformView.selectionEnd > binding.waveformView.selectionStart && binding.waveformView.selectionStart >= 0) 
                                    binding.waveformView.selectionEnd 
                                else 
                                    Int.MAX_VALUE
                                    
                while (mediaPlayer?.isPlaying == true) {
                    val currentMs = mediaPlayer?.currentPosition?.toLong() ?: 0L
                    
                    // Conversion MS -> Index Point
                    val currentIndex = ((currentMs * AudioHelper.POINTS_PER_SECOND) / 1000).toInt()
                    
                    binding.waveformView.playheadPos = currentIndex
                    binding.waveformView.invalidate()
                    runOnUiThread { updateCurrentTimeDisplay(currentIndex) }
                    autoScroll(currentIndex)
                    
                    if (binding.waveformView.selectionStart >= 0 && currentIndex >= endIndex) {
                        mediaPlayer?.pause()
                        break
                    }
                    delay(25) 
                }
                if (mediaPlayer?.isPlaying != true) stopAudio()
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Erreur lecture", Toast.LENGTH_SHORT).show()
        }
    }

    private fun autoScroll(sampleIdx: Int) {
        val px = binding.waveformView.sampleToPixel(sampleIdx)
        val screenCenter = binding.scroller.width / 2
        val target = (px - screenCenter).toInt().coerceAtLeast(0)
        if (abs(binding.scroller.scrollX - target) > 10) {
            binding.scroller.scrollTo(target, 0)
        }
    }

    private fun stopAudio() {
        playbackJob?.cancel()
        try {
            if (mediaPlayer?.isPlaying == true) mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {}
        mediaPlayer = null
        binding.btnPlay.setImageResource(R.drawable.ic_play)
    }

    private fun cutSelection() {
        val pcm = workingPcm ?: return
        val startIdx = binding.waveformView.selectionStart
        val endIdx = binding.waveformView.selectionEnd
        if (startIdx < 0 || endIdx <= startIdx) return

        stopAudio()
        
        // Calcul physique immédiat
        val samplesPerPoint = sampleRate / AudioHelper.POINTS_PER_SECOND
        val startS = startIdx * samplesPerPoint
        val endS = (endIdx * samplesPerPoint).coerceAtMost(pcm.size)
        
        val newPcm = ShortArray(pcm.size - (endS - startS))
        System.arraycopy(pcm, 0, newPcm, 0, startS)
        System.arraycopy(pcm, endS, newPcm, startS, pcm.size - endS)
        
        workingPcm = newPcm
        
        // Mise à jour visuelle instantanée
        refreshWaveformFromPcm(newPcm)
    }
    private fun refreshWaveformFromPcm(pcm: ShortArray) {
        lifecycleScope.launch(Dispatchers.Default) {
            val newWaveform = AudioHelper.generateWaveformFromPCM(pcm, sampleRate)
            withContext(Dispatchers.Main) {
                binding.waveformView.clearData()
                binding.waveformView.appendData(newWaveform)
                // Update durée
                val newMs = (pcm.size * 1000L) / sampleRate
                binding.txtDuration.text = formatTime(newMs)
            }
        }
    }

    private fun saveChanges() {
        val pcm = workingPcm ?: return
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = true

        lifecycleScope.launch(Dispatchers.IO) {
            val tmp = File(currentFile.parent, "tmp_save.m4a")
            val success = AudioHelper.savePCMToAAC(pcm, tmp, sampleRate)
            
            withContext(Dispatchers.Main) {
                if(success) {
                    currentFile.delete()
                    tmp.renameTo(currentFile)
                    Toast.makeText(this@EditorActivity, "Enregistré !", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this@EditorActivity, "Erreur de sauvegarde", Toast.LENGTH_SHORT).show()
                }
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun normalizeSelection() {
        val meta = metadata ?: return
        stopAudio()
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            
            val startIdx = if(binding.waveformView.selectionStart >= 0) binding.waveformView.selectionStart else 0
            val endIdx = if(binding.waveformView.selectionEnd > startIdx) binding.waveformView.selectionEnd else Int.MAX_VALUE
            
            val startMs = (startIdx * 1000L) / AudioHelper.POINTS_PER_SECOND
            // Si fin, on prend une grande valeur
            val endMs = if(endIdx == Int.MAX_VALUE) meta.duration else (endIdx * 1000L) / AudioHelper.POINTS_PER_SECOND
            
            val tmp = File(currentFile.parent, "tmp_norm.m4a")
            
            if(AudioHelper.normalizeAudio(currentFile, tmp, startMs, endMs, meta.sampleRate, 0.95f) {}) {
                currentFile.delete(); tmp.renameTo(currentFile)
                withContext(Dispatchers.Main) {
                    binding.waveformView.clearData()
                    loadWaveformStreaming() 
                    Toast.makeText(this@EditorActivity, "Normalisé", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    override fun onStop() { super.onStop(); stopAudio() }
}