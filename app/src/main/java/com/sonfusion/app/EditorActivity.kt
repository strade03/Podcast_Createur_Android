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
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    
    // Zoom vars
    private var zoomLevel = 1.0f
    private var screenWidth = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Récupérer la largeur de l'écran pour les calculs de zoom
        screenWidth = resources.displayMetrics.widthPixels

        val path = intent.getStringExtra("FILE_PATH")
        if (path == null) { finish(); return }
        currentFile = File(path)
        
        binding.txtFilename.text = currentFile.name.replace(Regex("^\\d{3}_"), "")
        binding.progressBar.visibility = View.VISIBLE
        
        loadWaveform()

        binding.btnPlay.setOnClickListener { if(!isPlaying) playAudio() else stopAudio() }
        binding.btnCut.setOnClickListener { cutSelection() }
        binding.btnNormalize.setOnClickListener { normalizeSelection() }
        binding.btnSave.setOnClickListener { saveFile() }
        
        // Gestion Zoom (Les boutons sont maintenant dans la toolbar)
        binding.btnZoomIn.setOnClickListener { applyZoom(zoomLevel * 1.5f) }
        binding.btnZoomOut.setOnClickListener { applyZoom(zoomLevel / 1.5f) }
        
        // Gestion Ré-enregistrement
        binding.btnReRecord.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Refaire l'enregistrement ?")
                .setMessage("L'audio actuel sera remplacé.")
                .setPositiveButton("Oui") { _, _ ->
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
                    } else {
                         Toast.makeText(this, "Impossible de déterminer la chronique", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Annuler", null)
                .show()
        }
    }

    private fun applyZoom(newZoom: Float) {
        // Bornes de zoom (x1 à x10)
        val clampedZoom = newZoom.coerceIn(1.0f, 10.0f)
        if (clampedZoom == zoomLevel) return
        
        zoomLevel = clampedZoom
        
        // On redimensionne la WaveformView à l'intérieur du ScrollView
        val params = binding.waveformView.layoutParams
        params.width = (screenWidth * zoomLevel).toInt()
        binding.waveformView.layoutParams = params
        binding.waveformView.requestLayout()
        binding.waveformView.invalidate()
    }

    private fun loadWaveform() {
        Thread {
            try {
                val data = AudioHelper.decodeToPCM(currentFile)
                pcmData = data
                runOnUiThread {
                    binding.waveformView.setWaveform(pcmData)
                    binding.progressBar.visibility = View.GONE
                    binding.txtDuration.text = formatTime(pcmData.size)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { Toast.makeText(this, "Erreur lecture", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }
    
    private fun formatTime(samples: Int): String {
        val sec = samples / 44100
        val m = sec / 60
        val s = sec % 60
        return String.format("%02d:%02d", m, s)
    }

    private fun playAudio() {
        if (pcmData.isEmpty()) return
        isPlaying = true
        binding.btnPlay.setImageResource(R.drawable.ic_pause)

        Thread {
            val sampleRate = 44100
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            audioTrack = AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf, AudioTrack.MODE_STREAM)

            audioTrack?.play()

            var startIdx = if (binding.waveformView.selectionStart >= 0) binding.waveformView.selectionStart else binding.waveformView.playheadPos
            startIdx = startIdx.coerceIn(0, pcmData.size)

            val bufferSize = 2048
            var offset = startIdx
            val endIdx = if (binding.waveformView.selectionEnd > startIdx) binding.waveformView.selectionEnd else pcmData.size

            while (isPlaying && offset < endIdx) {
                val len = minOf(bufferSize, endIdx - offset)
                audioTrack?.write(pcmData, offset, len)
                offset += len
                
                if (offset % 8820 == 0) { 
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
        val x = (sampleIdx.toFloat() / totalSamples) * viewWidth
        
        val screenCenter = screenWidth / 2
        val scrollX = (x - screenCenter).toInt()
        
        binding.scroller.smoothScrollTo(scrollX, 0)
    }

    private fun stopAudio() {
        isPlaying = false
    }
    
    private fun cutSelection() {
        val start = binding.waveformView.selectionStart
        val end = binding.waveformView.selectionEnd
        if (start < 0 || end <= start) return

        AlertDialog.Builder(this)
            .setTitle("Couper ?")
            .setPositiveButton("Oui") { _, _ ->
                 val newPcm = ShortArray(pcmData.size - (end - start))
                System.arraycopy(pcmData, 0, newPcm, 0, start)
                System.arraycopy(pcmData, end, newPcm, start, pcmData.size - end)

                pcmData = newPcm
                binding.waveformView.setWaveform(pcmData)
                binding.waveformView.clearSelection()
                binding.txtDuration.text = formatTime(pcmData.size)
            }
            .setNegativeButton("Non", null).show()
    }

    private fun normalizeSelection() {
         val start = if (binding.waveformView.selectionStart >= 0) binding.waveformView.selectionStart else 0
        val end = if (binding.waveformView.selectionEnd > start) binding.waveformView.selectionEnd else pcmData.size

        var maxVal = 0
        for (i in start until end) {
            if (abs(pcmData[i].toInt()) > maxVal) maxVal = abs(pcmData[i].toInt())
        }

        if (maxVal > 0) {
            val factor = 32767f / maxVal
            for (i in start until end) {
                pcmData[i] = (pcmData[i] * factor).toInt().toShort()
            }
            binding.waveformView.invalidate()
            Toast.makeText(this, "Normalisé !", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveFile() {
        binding.progressBar.visibility = View.VISIBLE
        Thread {
            val success = AudioHelper.savePCMToAAC(pcmData, currentFile)
            runOnUiThread {
                binding.progressBar.visibility = View.GONE
                if (success) {
                    Toast.makeText(this, "Sauvegardé", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }.start()
    }
    
    override fun onStop() {
        super.onStop()
        stopAudio()
    }
}