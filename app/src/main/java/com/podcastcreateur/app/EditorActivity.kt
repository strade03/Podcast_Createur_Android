package com.podcastcreateur.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.podcastcreateur.app.databinding.ActivityEditorBinding
import java.io.File

class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var currentFile: File
    private var pcmData: ShortArray = ShortArray(0)
    
    private var fileSampleRate = 44100 
    private var metadata: AudioMetadata? = null
    
    private var isPlaying = false
    private var currentZoom = 1.0f
    private var player: android.media.MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra("FILE_PATH")
        if (path == null) { finish(); return }
        currentFile = File(path)
        
        updateFileInfo()
        loadWaveform()

        binding.btnPlay.setOnClickListener { if(!isPlaying) playAudio() else stopAudio() }
        
        binding.btnCut.setOnClickListener { performCut() }
        binding.btnNormalize.setOnClickListener { performNormalize() }
        binding.btnSave.setOnClickListener { finish() }
        
        binding.btnZoomIn.setOnClickListener { applyZoom(currentZoom * 1.5f) }
        binding.btnZoomOut.setOnClickListener { applyZoom(currentZoom / 1.5f) }
        
        binding.btnReRecord.setOnClickListener { confirmReRecord() }
    }

    private fun updateFileInfo() {
        binding.txtFilename.text = currentFile.name.replace(Regex("^\\d{3}_"), "")
    }

    private fun performCut() {
        if (metadata == null || pcmData.isEmpty()) return
        
        val startIdx = binding.waveformView.selectionStart
        val endIdx = binding.waveformView.selectionEnd
        
        if (startIdx < 0 || endIdx <= startIdx) {
             Toast.makeText(this, "Sélectionnez la zone à GARDER", Toast.LENGTH_LONG).show()
             return
        }

        // CORRECTION COMPILATION : On utilise durationUs (Long) converti en Double explicitement
        val durationSec = metadata!!.durationUs.toDouble() / 1_000_000.0
        val totalGraphPoints = pcmData.size
        
        if (totalGraphPoints == 0) return

        // Calcul précis avec des Doubles pour éviter l'ambiguïté
        val startTime = (startIdx.toDouble() / totalGraphPoints.toDouble()) * durationSec
        val endTime = (endIdx.toDouble() / totalGraphPoints.toDouble()) * durationSec
        val keepDuration = endTime - startTime

        showLoading("Découpage en cours...")

        val tempFile = File(cacheDir, "temp_cut.m4a")
        
        FFmpegHelper.cutAudio(currentFile, tempFile, startTime, keepDuration) { success ->
            runOnUiThread {
                hideLoading()
                if (success) {
                    if (currentFile.exists()) currentFile.delete()
                    tempFile.copyTo(currentFile)
                    tempFile.delete()
                    
                    stopAudio()
                    binding.waveformView.clearSelection()
                    loadWaveform()
                    Toast.makeText(this, "Fichier découpé", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Erreur lors du découpage", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun performNormalize() {
        showLoading("Normalisation du volume...")
        val tempFile = File(cacheDir, "temp_norm.m4a")
        
        FFmpegHelper.normalizeAudio(currentFile, tempFile) { success ->
            runOnUiThread {
                hideLoading()
                if (success) {
                    if (currentFile.exists()) currentFile.delete()
                    tempFile.copyTo(currentFile)
                    tempFile.delete()
                    
                    stopAudio()
                    loadWaveform()
                    Toast.makeText(this, "Volume normalisé", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Erreur normalisation", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private var progressDialog: AlertDialog? = null
    private fun showLoading(msg: String) {
        stopAudio()
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Traitement")
        builder.setMessage(msg)
        builder.setCancelable(false)
        progressDialog = builder.create()
        progressDialog?.show()
    }

    private fun hideLoading() {
        progressDialog?.dismiss()
    }

    private fun applyZoom(newZoom: Float) {
        val clampedZoom = newZoom.coerceIn(1.0f, 50.0f)
        currentZoom = clampedZoom
        binding.waveformView.setZoomLevel(currentZoom)
    }

    private fun loadWaveform() {
        binding.progressBar.visibility = View.VISIBLE
        Thread {
            try {
                metadata = AudioHelper.getAudioMetadata(currentFile)
                if (metadata == null) {
                    runOnUiThread { finish() }
                    return@Thread
                }
                fileSampleRate = metadata!!.sampleRate
                val preview = AudioHelper.loadWaveformPreview(currentFile)
                pcmData = preview.data
                
                runOnUiThread {
                    binding.waveformView.setWaveform(pcmData)
                    binding.progressBar.visibility = View.GONE
                    
                    // CORRECTION : Conversion Long -> Long pour l'affichage
                    val durationSecLong = metadata!!.durationUs / 1_000_000
                    binding.txtDuration.text = formatDuration(durationSecLong)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun formatDuration(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return String.format("%02d:%02d", m, s)
    }

    private fun playAudio() {
        if (metadata == null) return
        isPlaying = true
        binding.btnPlay.setImageResource(R.drawable.ic_stop_read)

        Thread {
            try {
                player = android.media.MediaPlayer()
                player?.setDataSource(currentFile.absolutePath)
                player?.prepare()
                player?.start()
                
                val duration = player?.duration ?: 0
                val startTime = System.currentTimeMillis()
                
                while (isPlaying && player?.isPlaying == true) {
                    val elapsed = player?.currentPosition ?: 0
                    if (duration > 0 && pcmData.isNotEmpty()) {
                         val progress = (elapsed.toFloat() / duration * pcmData.size).toInt()
                         runOnUiThread {
                            binding.waveformView.playheadPos = progress.coerceIn(0, pcmData.size)
                            binding.waveformView.invalidate()
                            autoScroll(progress)
                        }
                    }
                    Thread.sleep(50)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            stopAudio()
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

    private fun stopAudio() { 
        isPlaying = false
        try {
            player?.stop()
            player?.release()
        } catch(e: Exception) {}
        player = null
        
        runOnUiThread {
            binding.btnPlay.setImageResource(R.drawable.ic_play)
        }
    }
    
    private fun confirmReRecord() {
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

    override fun onStop() { 
        super.onStop()
        stopAudio()
    }
}