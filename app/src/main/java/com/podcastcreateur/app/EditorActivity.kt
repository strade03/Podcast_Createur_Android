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

// Classe pour stocker les modifications en attente
data class PendingEdit(
    val type: EditType,
    val startIndex: Int,
    val endIndex: Int,
    val timestamp: Long = System.currentTimeMillis()
)

enum class EditType {
    CUT, NORMALIZE
}

class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var currentFile: File
    
    private var metadata: AudioMetadata? = null
    private var mediaPlayer: MediaPlayer? = null
    private var playbackJob: Job? = null
    
    private var currentZoom = 1.0f
    
    // 🔥 NOUVEAUTÉ : Liste des modifications en attente
    private val pendingEdits = mutableListOf<PendingEdit>()
    private var hasUnsavedChanges = false

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
        
        // 🔥 MODIFICATION : Sauvegarde différée
        binding.btnSave.setOnClickListener { 
            if (hasUnsavedChanges) {
                saveAllChanges()
            } else {
                Toast.makeText(this, "Aucune modification à sauvegarder", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        
        binding.btnZoomIn.setOnClickListener { applyZoom(currentZoom * 1.5f) }
        binding.btnZoomOut.setOnClickListener { applyZoom(currentZoom / 1.5f) }
        
        binding.btnReRecord.setOnClickListener {
            if (hasUnsavedChanges) {
                AlertDialog.Builder(this)
                    .setTitle("Modifications non sauvegardées")
                    .setMessage("Voulez-vous sauvegarder avant de refaire l'enregistrement ?")
                    .setPositiveButton("Sauvegarder") { _, _ ->
                        saveAllChanges(andThenReRecord = true)
                    }
                    .setNegativeButton("Ne pas sauvegarder") { _, _ ->
                        launchReRecord()
                    }
                    .setNeutralButton("Annuler", null)
                    .show()
            } else {
                launchReRecord()
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
        val ms = index.toLong() * (1000 / AudioHelper.POINTS_PER_SECOND)
        runOnUiThread {
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
                    val currentIndex = ((currentMs * AudioHelper.POINTS_PER_SECOND) / 1000).toInt()
                    
                    binding.waveformView.playheadPos = currentIndex
                    binding.waveformView.invalidate()
                    updateCurrentTimeDisplay(currentIndex)
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

    // 🔥 MODIFICATION : Coupe instantanée (visuelle uniquement)
    private fun cutSelection() {
        val meta = metadata ?: return
        val startIdx = binding.waveformView.selectionStart
        val endIdx = binding.waveformView.selectionEnd
        if (startIdx < 0 || endIdx <= startIdx) {
            Toast.makeText(this, "Sélectionnez une zone à couper", Toast.LENGTH_SHORT).show()
            return
        }
        
        stopAudio()
        
        // Ajouter à la liste des modifications en attente
        pendingEdits.add(PendingEdit(EditType.CUT, startIdx, endIdx))
        hasUnsavedChanges = true
        
        // Mise à jour visuelle instantanée de la waveform
        binding.waveformView.applyCutVisually(startIdx, endIdx)
        
        // Réinitialiser la sélection
        binding.waveformView.clearSelection()
        
        Toast.makeText(this, "✂️ Coupe marquée (appuyez sur Sauvegarder pour appliquer)", Toast.LENGTH_SHORT).show()
        
        // Mettre à jour le titre pour indiquer les modifications
        binding.txtFilename.text = "* " + currentFile.name.replace(Regex("^\\d{3}_"), "")
    }

    // 🔥 MODIFICATION : Normalisation instantanée (marquée uniquement)
    private fun normalizeSelection() {
        val meta = metadata ?: return
        stopAudio()
        
        val startIdx = if(binding.waveformView.selectionStart >= 0) binding.waveformView.selectionStart else 0
        val endIdx = if(binding.waveformView.selectionEnd > startIdx) binding.waveformView.selectionEnd else Int.MAX_VALUE
        
        pendingEdits.add(PendingEdit(EditType.NORMALIZE, startIdx, endIdx))
        hasUnsavedChanges = true
        
        Toast.makeText(this, "🔊 Normalisation marquée (appuyez sur Sauvegarder pour appliquer)", Toast.LENGTH_SHORT).show()
        binding.txtFilename.text = "* " + currentFile.name.replace(Regex("^\\d{3}_"), "")
    }
    
    // 🔥 NOUVEAUTÉ : Sauvegarde de toutes les modifications
    private fun saveAllChanges(andThenReRecord: Boolean = false) {
        if (pendingEdits.isEmpty()) {
            Toast.makeText(this, "Aucune modification à sauvegarder", Toast.LENGTH_SHORT).show()
            if (andThenReRecord) launchReRecord()
            else finish()
            return
        }
        
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Traitement en cours...")
            .setMessage("Application des modifications (${pendingEdits.size} opération(s))")
            .setCancelable(false)
            .create()
        progressDialog.show()
        
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch(Dispatchers.IO) {
            val meta = metadata ?: return@launch
            
            try {
                // Appliquer toutes les modifications dans l'ordre
                var currentWorkingFile = currentFile
                
                pendingEdits.forEachIndexed { index, edit ->
                    withContext(Dispatchers.Main) {
                        progressDialog.setMessage("Traitement ${index + 1}/${pendingEdits.size}...")
                    }
                    
                    val tmpFile = File(currentFile.parent, "tmp_edit_${System.currentTimeMillis()}.m4a")
                    
                    val success = when (edit.type) {
                        EditType.CUT -> {
                            val samplesPerPoint = meta.sampleRate / AudioHelper.POINTS_PER_SECOND
                            val startSample = edit.startIndex * samplesPerPoint
                            val endSample = edit.endIndex * samplesPerPoint
                            AudioHelper.deleteRegionStreaming(currentWorkingFile, tmpFile, startSample, endSample)
                        }
                        EditType.NORMALIZE -> {
                            val startMs = (edit.startIndex * 1000L) / AudioHelper.POINTS_PER_SECOND
                            val endMs = if(edit.endIndex == Int.MAX_VALUE) meta.duration 
                                       else (edit.endIndex * 1000L) / AudioHelper.POINTS_PER_SECOND
                            AudioHelper.normalizeAudio(currentWorkingFile, tmpFile, startMs, endMs, meta.sampleRate, 0.95f) {}
                        }
                    }
                    
                    if (success) {
                        if (currentWorkingFile != currentFile) {
                            currentWorkingFile.delete()
                        }
                        currentWorkingFile = tmpFile
                    } else {
                        tmpFile.delete()
                        throw Exception("Échec de l'opération ${edit.type}")
                    }
                }
                
                // Remplacer le fichier original
                currentFile.delete()
                currentWorkingFile.renameTo(currentFile)
                
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    binding.progressBar.visibility = View.GONE
                    pendingEdits.clear()
                    hasUnsavedChanges = false
                    
                    Toast.makeText(this@EditorActivity, "✅ Modifications sauvegardées", Toast.LENGTH_SHORT).show()
                    
                    if (andThenReRecord) {
                        launchReRecord()
                    } else {
                        finish()
                    }
                }
                
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@EditorActivity, "❌ Erreur : ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun launchReRecord() {
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
    }
    
    override fun onStop() { 
        super.onStop()
        stopAudio()
    }
    
    override fun onBackPressed() {
        if (hasUnsavedChanges) {
            AlertDialog.Builder(this)
                .setTitle("Modifications non sauvegardées")
                .setMessage("Voulez-vous sauvegarder vos modifications ?")
                .setPositiveButton("Sauvegarder") { _, _ ->
                    saveAllChanges()
                }
                .setNegativeButton("Ne pas sauvegarder") { _, _ ->
                    super.onBackPressed()
                }
                .setNeutralButton("Annuler", null)
                .show()
        } else {
            super.onBackPressed()
        }
    }
}