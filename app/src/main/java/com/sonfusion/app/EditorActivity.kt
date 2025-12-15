package com.podcastcreateur.app

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
    }

    private fun loadWaveform() {
        Thread {
            try {
                // Utilisation de AudioHelper pour décoder (compatible m4a/mp3)
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

            var startIdx = 0
            if (binding.waveformView.selectionStart >= 0) {
                startIdx = binding.waveformView.selectionStart
            } else {
                startIdx = binding.waveformView.playheadPos
            }
            startIdx = startIdx.coerceIn(0, pcmData.size)

            val bufferSize = 2048
            var offset = startIdx
            val endIdx = if (binding.waveformView.selectionEnd > startIdx) binding.waveformView.selectionEnd else pcmData.size

            while (isPlaying && offset < endIdx) {
                val len = minOf(bufferSize, endIdx - offset)
                audioTrack?.write(pcmData, offset, len)
                offset += len
                
                if (offset % 8820 == 0) { 
                     runOnUiThread { binding.waveformView.playheadPos = offset; binding.waveformView.invalidate() }
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

    private fun stopAudio() {
        isPlaying = false
    }

    private fun cutSelection() {
        val start = binding.waveformView.selectionStart
        val end = binding.waveformView.selectionEnd
        if (start < 0 || end <= start) return

        AlertDialog.Builder(this)
            .setTitle("Couper ?")
            .setMessage("Cette action supprime la partie sélectionnée.")
            .setPositiveButton("Confirmer") { _, _ ->
                 val newPcm = ShortArray(pcmData.size - (end - start))
                System.arraycopy(pcmData, 0, newPcm, 0, start)
                System.arraycopy(pcmData, end, newPcm, start, pcmData.size - end)

                pcmData = newPcm
                binding.waveformView.setWaveform(pcmData)
                binding.waveformView.clearSelection()
                binding.txtDuration.text = formatTime(pcmData.size)
                Toast.makeText(this, "Coupé !", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuler", null)
            .show()
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
            // Sauvegarde en AAC
            val success = AudioHelper.savePCMToAAC(pcmData, currentFile)
            runOnUiThread {
                binding.progressBar.visibility = View.GONE
                if (success) {
                    Toast.makeText(this, "Sauvegardé", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this, "Erreur sauvegarde", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
    
    override fun onStop() {
        super.onStop()
        stopAudio()
    }
}