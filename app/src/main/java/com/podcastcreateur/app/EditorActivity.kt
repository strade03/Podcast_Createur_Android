package com.podcastcreateur.app

import android.media.*
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.podcastcreateur.app.databinding.ActivityEditorBinding
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import kotlin.math.abs

class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var sourceFile: File
    private lateinit var projectDir: File
    
    // Données du projet courant
    private var projectData: AudioProjectData? = null
    
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var playbackJob: Job? = null

    private var currentZoom = 1.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra("FILE_PATH")
        if (path == null) { finish(); return }
        sourceFile = File(path)
        projectDir = sourceFile.parentFile ?: filesDir

        binding.txtFilename.text = sourceFile.name

        // Initialisation UI
        binding.btnPlay.setOnClickListener { togglePlay() }
        binding.btnCut.setOnClickListener { performCut() }
        binding.btnSave.setOnClickListener { performSave() }
        binding.btnZoomIn.setOnClickListener { applyZoom(currentZoom * 1.5f) }
        binding.btnZoomOut.setOnClickListener { applyZoom(currentZoom / 1.5f) }
        binding.btnNormalize.setOnClickListener { Toast.makeText(this, "À implémenter sur RAW", Toast.LENGTH_SHORT).show() }
        
        binding.waveformView.onPositionChanged = { idx -> updateTimeDisplay(idx) }

        // Démarrer l'initialisation lourde
        startLoadingProcess()
    }

    private fun startLoadingProcess() {
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.max = 100
        setControlsEnabled(false)

        lifecycleScope.launch(Dispatchers.IO) {
            // Conversion MP3 -> RAW (Peut prendre du temps pour 15min/1h)
            // Mais c'est fait UNE SEULE FOIS.
            val data = AudioHelper.prepareProject(sourceFile, projectDir) { progress ->
                runOnUiThread { binding.progressBar.progress = progress }
            }

            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.GONE
                if (data != null) {
                    projectData = data
                    // Initialiser la vue
                    binding.waveformView.initialize(data.peaks.size.toLong())
                    binding.waveformView.appendData(data.peaks)
                    binding.txtDuration.text = formatTime(data.durationMs)
                    setControlsEnabled(true)
                    Toast.makeText(this@EditorActivity, "Prêt !", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@EditorActivity, "Erreur de chargement", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        binding.btnPlay.isEnabled = enabled
        binding.btnCut.isEnabled = enabled
        binding.btnSave.isEnabled = enabled
        binding.waveformView.isEnabled = enabled
    }

    // --- LECTURE DIRECTE DEPUIS LE DISQUE (STREAMING) ---
    private fun togglePlay() {
        if (isPlaying) {
            stopAudio()
        } else {
            playAudioRaw()
        }
    }

    private fun playAudioRaw() {
        val data = projectData ?: return
        if (!data.rawFile.exists()) return

        stopAudio() // Sécurité

        val minBuf = AudioTrack.getMinBufferSize(data.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack.Builder()
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(data.sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            
        audioTrack?.play()
        isPlaying = true
        binding.btnPlay.setImageResource(R.drawable.ic_stop_read)

        playbackJob = lifecycleScope.launch(Dispatchers.IO) {
            val fis = FileInputStream(data.rawFile)
            val buffer = ByteArray(minBuf)
            
            // Calculer où commencer (Seek)
            // 1 point = 20ms. 
            // 1 sec = SampleRate * 2 bytes
            val bytesPerSec = data.sampleRate * 2
            val bytesPerPoint = bytesPerSec / AudioHelper.POINTS_PER_SECOND
            
            var currentBytePos = if(binding.waveformView.selectionStart >= 0)
                                    binding.waveformView.selectionStart.toLong() * bytesPerPoint
                                 else 
                                    binding.waveformView.playheadPos.toLong() * bytesPerPoint
            
            // Sauter dans le fichier
            fis.skip(currentBytePos)
            
            // Limite de fin
            val endBytePos = if (binding.waveformView.selectionEnd > binding.waveformView.selectionStart && binding.waveformView.selectionStart >= 0)
                                binding.waveformView.selectionEnd.toLong() * bytesPerPoint
                             else
                                data.rawFile.length()

            try {
                while (isPlaying && currentBytePos < endBytePos) {
                    val remaining = endBytePos - currentBytePos
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    
                    val read = fis.read(buffer, 0, toRead)
                    if (read == -1) break
                    
                    val written = audioTrack?.write(buffer, 0, read) ?: 0
                    if (written < 0) break
                    
                    currentBytePos += read
                    
                    // UI Update
                    val currentPoint = (currentBytePos / bytesPerPoint).toInt()
                    withContext(Dispatchers.Main) {
                        binding.waveformView.playheadPos = currentPoint
                        binding.waveformView.invalidate()
                        updateTimeDisplay(currentPoint)
                        autoScroll(currentPoint)
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
            finally {
                fis.close()
                withContext(Dispatchers.Main) { stopAudio() }
            }
        }
    }

    private fun stopAudio() {
        isPlaying = false
        playbackJob?.cancel()
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {}
        audioTrack = null
        binding.btnPlay.setImageResource(R.drawable.ic_play)
    }

    // --- EDITION RAPIDE SUR DISQUE ---
    private fun performCut() {
        val data = projectData ?: return
        val start = binding.waveformView.selectionStart
        val end = binding.waveformView.selectionEnd
        
        if (start < 0 || end <= start) return
        stopAudio()
        
        binding.progressBar.visibility = View.VISIBLE
        setControlsEnabled(false)

        lifecycleScope.launch(Dispatchers.IO) {
            // 1. Coupe physique du fichier RAW (rapide)
            val success = AudioHelper.cutRawFile(data.rawFile, data.sampleRate, start, end)
            
            if (success) {
                // 2. Recalculer l'affichage (On retire juste les points de la liste, c'est instantané en RAM)
                // Note : Pour faire simple ici, on recharge tout proprement
                // Idéalement on manipulerait le tableau peaks directement
                val newData = AudioHelper.prepareProject(sourceFile, projectDir) { /* ignore */ }
                
                withContext(Dispatchers.Main) {
                    if (newData != null) {
                        projectData = newData
                        binding.waveformView.clearData()
                        binding.waveformView.initialize(newData.peaks.size.toLong())
                        binding.waveformView.appendData(newData.peaks)
                        binding.waveformView.clearSelection()
                        binding.waveformView.playheadPos = start
                    }
                    binding.progressBar.visibility = View.GONE
                    setControlsEnabled(true)
                }
            }
        }
    }

    private fun performSave() {
        val data = projectData ?: return
        stopAudio()
        binding.progressBar.visibility = View.VISIBLE
        setControlsEnabled(false)
        
        lifecycleScope.launch(Dispatchers.IO) {
            // RAW -> M4A final
            val success = AudioHelper.exportRawToM4A(data.rawFile, sourceFile, data.sampleRate)
            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.GONE
                setControlsEnabled(true)
                if (success) {
                    Toast.makeText(this@EditorActivity, "Sauvegardé !", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this@EditorActivity, "Erreur sauvegarde", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateTimeDisplay(index: Int) {
        val ms = index.toLong() * (1000 / AudioHelper.POINTS_PER_SECOND)
        binding.txtCurrentTime.text = formatTime(ms)
    }

    private fun formatTime(ms: Long): String {
        val s = (ms / 1000).toInt()
        return String.format("%02d:%02d", s / 60, s % 60)
    }

    private fun applyZoom(newZoom: Float) {
        currentZoom = newZoom.coerceIn(0.1f, 50.0f)
        binding.waveformView.setZoomLevel(currentZoom)
    }
    
    private fun autoScroll(idx: Int) {
        val px = binding.waveformView.sampleToPixel(idx)
        val target = (px - binding.scroller.width / 2).toInt().coerceAtLeast(0)
        if (abs(binding.scroller.scrollX - target) > 20) binding.scroller.scrollTo(target, 0)
    }

    override fun onStop() { super.onStop(); stopAudio() }
}