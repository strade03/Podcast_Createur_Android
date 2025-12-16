package com.podcastcreateur.app

import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.podcastcreateur.app.databinding.ActivityEditorBinding
import java.io.File
import kotlin.math.abs

class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var currentFile: File
    private var pcmData: ShortArray = ShortArray(0)
    
    private var fileSampleRate = 44100 
    
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var currentZoom = 1.0f
    
    // Flag pour savoir si on a chargé en mode dégradé
    private var isDownsampled = false

    companion object {
        private const val LARGE_FILE_THRESHOLD = 50 * 1024 * 1024 // 50 Mo
        private const val WARNING_THRESHOLD = 100 * 1024 * 1024 // 100 Mo
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra("FILE_PATH")
        if (path == null) { finish(); return }
        currentFile = File(path)
        
        binding.txtFilename.text = currentFile.name.replace(Regex("^\\d{3}_"), "")
        
        // Vérifier la taille du fichier AVANT de charger
        checkFileSizeAndLoad()

        binding.btnPlay.setOnClickListener { if(!isPlaying) playAudio() else stopAudio() }
        binding.btnCut.setOnClickListener { cutSelection() }
        binding.btnNormalize.setOnClickListener { normalizeSelection() }
        binding.btnSave.setOnClickListener { saveFile() }
        
        binding.btnZoomIn.setOnClickListener { 
            applyZoom(currentZoom * 1.5f)
        }
        binding.btnZoomOut.setOnClickListener { 
            applyZoom(currentZoom / 1.5f)
        }
        
        binding.btnReRecord.setOnClickListener {
             AlertDialog.Builder(this)
                .setTitle("Refaire l'enregistrement ?")
                .setMessage("L'audio actuel sera remplacé.")
                .setPositiveButton("Oui") { _, _ ->
                    stopAudio()
                    val regex = Regex("^(\\d{3}_)(.*)\\.(.*)$")
                    val match = regex.find(currentFile.name)
                    if (match != null) {
                        val (prefix, name, _) = match.destructured
                        val projectPath = currentFile.parent
                        val scriptPath = File(projectPath, "$prefix$name.txt").absolutePath
                        val intent = Intent(this, RecorderActivity::class.java)
                        intent.putExtra("PROJECT_PATH", projectPath)
                        intent.putExtra("CHRONICLE_NAME", name)
                        intent.putExtra("CHRONICLE_PREFIX", prefix)
                        intent.putExtra("SCRIPT_PATH", scriptPath)
                        startActivity(intent)
                        finish()
                    }
                }
                .setNegativeButton("Annuler", null).show()
        }
    }

    private fun checkFileSizeAndLoad() {
        val fileSizeMb = currentFile.length() / (1024 * 1024)
        
        when {
            currentFile.length() > WARNING_THRESHOLD -> {
                // Fichier très gros : avertir l'utilisateur
                AlertDialog.Builder(this)
                    .setTitle("Fichier volumineux")
                    .setMessage("Ce fichier fait ${fileSizeMb} Mo. Le chargement peut prendre du temps et la qualité sera réduite pour l'affichage.\n\nContinuer ?")
                    .setPositiveButton("Oui") { _, _ ->
                        isDownsampled = true
                        loadWaveform()
                    }
                    .setNegativeButton("Annuler") { _, _ ->
                        finish()
                    }
                    .setCancelable(false)
                    .show()
            }
            currentFile.length() > LARGE_FILE_THRESHOLD -> {
                // Fichier gros : charger en mode dégradé sans demander
                isDownsampled = true
                Toast.makeText(this, "Fichier volumineux : chargement optimisé", Toast.LENGTH_LONG).show()
                loadWaveform()
            }
            else -> {
                // Fichier normal
                loadWaveform()
            }
        }
    }

    private fun applyZoom(newZoom: Float) {
        val clampedZoom = newZoom.coerceIn(1.0f, 20.0f)
        
        val oldWidth = binding.waveformView.width
        val playheadRelativePos = if (oldWidth > 0 && pcmData.isNotEmpty()) {
            binding.waveformView.playheadPos.toFloat() / pcmData.size
        } else {
            0.5f
        }
        
        currentZoom = clampedZoom
        binding.waveformView.setZoomLevel(currentZoom)
        
        binding.waveformView.post {
            val newWidth = binding.waveformView.width
            val screenWidth = resources.displayMetrics.widthPixels
            val playheadX = playheadRelativePos * newWidth
            val targetScrollX = (playheadX - screenWidth / 2).toInt().coerceAtLeast(0)
            binding.scroller.smoothScrollTo(targetScrollX, 0)
        }
    }

    private fun loadWaveform() {
        binding.progressBar.visibility = View.VISIBLE
        
        Thread {
            try {
                val content = if (isDownsampled) {
                    // Charger avec limitation de samples
                    AudioHelper.decodeToPCM(currentFile, 44100 * 60 * 10) // Max 10 minutes
                } else {
                    // Charger normalement
                    AudioHelper.decodeToPCM(currentFile, Int.MAX_VALUE)
                }
                
                pcmData = content.data
                fileSampleRate = content.sampleRate
                
                runOnUiThread {
                    binding.waveformView.setWaveform(pcmData)
                    binding.progressBar.visibility = View.GONE
                    binding.txtDuration.text = formatTime(pcmData.size)
                    
                    if (isDownsampled) {
                        Toast.makeText(this, "Mode aperçu : qualité réduite pour l'affichage", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: OutOfMemoryError) {
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    AlertDialog.Builder(this)
                        .setTitle("Fichier trop volumineux")
                        .setMessage("Ce fichier est trop gros pour être édité sur cet appareil. Utilisez un logiciel sur ordinateur comme Audacity.")
                        .setPositiveButton("OK") { _, _ -> finish() }
                        .show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this, "Erreur de chargement", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun formatTime(samples: Int): String {
        if (fileSampleRate == 0) return "00:00"
        val sec = samples / fileSampleRate
        val m = sec / 60
        val s = sec % 60
        return String.format("%02d:%02d", m, s)
    }

    private fun playAudio() {
        if (pcmData.isEmpty()) return
        isPlaying = true
        binding.btnPlay.setImageResource(R.drawable.ic_stop_read)

        Thread {
            val minBuf = AudioTrack.getMinBufferSize(fileSampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC, 
                fileSampleRate,
                AudioFormat.CHANNEL_OUT_MONO, 
                AudioFormat.ENCODING_PCM_16BIT, 
                minBuf, 
                AudioTrack.MODE_STREAM
            )

            audioTrack?.play()

            var startIdx = if (binding.waveformView.selectionStart >= 0) binding.waveformView.selectionStart else binding.waveformView.playheadPos
            startIdx = startIdx.coerceIn(0, pcmData.size)

            val bufferSize = 4096
            var offset = startIdx
            val endIdx = if (binding.waveformView.selectionEnd > startIdx) binding.waveformView.selectionEnd else pcmData.size

            while (isPlaying && offset < endIdx) {
                val len = minOf(bufferSize, endIdx - offset)
                audioTrack?.write(pcmData, offset, len)
                offset += len
                
                if (offset % (fileSampleRate / 5) == 0) { 
                     runOnUiThread { 
                         binding.waveformView.playheadPos = offset
                         binding.waveformView.invalidate()
                         autoScroll(offset)
                     }
                }
            }

            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            isPlaying = false
            runOnUiThread { 
                binding.btnPlay.setImageResource(R.drawable.ic_play) 
                if (offset >= endIdx) binding.waveformView.playheadPos = startIdx
                binding.waveformView.invalidate()
            }
        }.start()
    }
    
    private fun autoScroll(sampleIdx: Int) {
        val totalSamples = pcmData.size
        if (totalSamples == 0) return
        val viewWidth = binding.waveformView.width 
        val screenW = resources.displayMetrics.widthPixels
        val x = (sampleIdx.toFloat() / totalSamples) * viewWidth
        val scrollX = (x - screenW / 2).toInt()
        binding.scroller.smoothScrollTo(scrollX, 0)
    }

    private fun stopAudio() { isPlaying = false }
    
    private fun cutSelection() {
        val start = binding.waveformView.selectionStart
        val end = binding.waveformView.selectionEnd
        if (start < 0 || end <= start) return
        
        if (isDownsampled) {
            Toast.makeText(this, "Édition impossible en mode aperçu", Toast.LENGTH_LONG).show()
            return
        }

        AlertDialog.Builder(this).setTitle("Couper ?").setPositiveButton("Oui") { _, _ ->
             val newPcm = ShortArray(pcmData.size - (end - start))
            System.arraycopy(pcmData, 0, newPcm, 0, start)
            System.arraycopy(pcmData, end, newPcm, start, pcmData.size - end)
            pcmData = newPcm
            binding.waveformView.setWaveform(pcmData)
            binding.waveformView.clearSelection()
            binding.txtDuration.text = formatTime(pcmData.size)
        }.setNegativeButton("Non", null).show()
    }

    private fun normalizeSelection() {
        if (isDownsampled) {
            Toast.makeText(this, "Édition impossible en mode aperçu", Toast.LENGTH_LONG).show()
            return
        }
        
        val start = if (binding.waveformView.selectionStart >= 0) binding.waveformView.selectionStart else 0
        val end = if (binding.waveformView.selectionEnd > start) binding.waveformView.selectionEnd else pcmData.size
        var maxVal = 0
        for (i in start until end) {
            if (abs(pcmData[i].toInt()) > maxVal) maxVal = abs(pcmData[i].toInt())
        }
        if (maxVal > 0) {
            val factor = 32767f / maxVal
            for (i in start until end) pcmData[i] = (pcmData[i] * factor).toInt().toShort()
            binding.waveformView.invalidate()
            Toast.makeText(this, "Normalisé", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveFile() {
        if (isDownsampled) {
            Toast.makeText(this, "Sauvegarde impossible en mode aperçu", Toast.LENGTH_LONG).show()
            return
        }
        
        binding.progressBar.visibility = View.VISIBLE
        Thread {
            val success = AudioHelper.savePCMToAAC(pcmData, currentFile, fileSampleRate)
            runOnUiThread {
                binding.progressBar.visibility = View.GONE
                if (success) {
                    Toast.makeText(this, "Sauvegardé", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }.start()
    }
    
    override fun onStop() { super.onStop(); stopAudio() }
}