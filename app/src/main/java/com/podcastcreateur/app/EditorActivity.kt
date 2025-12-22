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
    
    private var currentZoom = 1.0f

    // ✅ PCM chargé paresseusement (lazy loading)
    private var workingPcm: ShortArray? = null
    private var isPcmReady = false
    private var sampleRate = 44100
    
    // ✅ Job de chargement PCM en arrière-plan
    private var pcmLoadingJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra("FILE_PATH")
        if (path == null) { finish(); return }
        currentFile = File(path)
        
        binding.txtFilename.text = currentFile.name.replace(Regex("^\\d{3}_"), "")
        binding.waveformView.setZoomLevel(currentZoom)
        
        // ✅ PHASE 1 : Affichage ULTRA RAPIDE de la waveform
        loadWaveformFast()
        
        // ✅ PHASE 2 : Chargement du PCM en arrière-plan (silencieux)
        startBackgroundPcmLoading()

        binding.waveformView.onPositionChanged = { index -> updateCurrentTimeDisplay(index)}

        binding.btnPlay.setOnClickListener { 
            if(mediaPlayer?.isPlaying == true) stopAudio() else playAudio() 
        }
        
        binding.btnCut.setOnClickListener { cutSelection() }
        binding.btnNormalize.setOnClickListener { normalizeSelection() }
        binding.btnSave.setOnClickListener { 
            saveChanges()
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
        
        // ✅ Boutons d'édition désactivés au début
        updateEditButtons(false)
    }

    /**
     * ⚡ PHASE 1 : Affichage INSTANTANÉ de la waveform
     * Utilise le cache ou streaming optimisé
     */
    private fun loadWaveformFast() {
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch(Dispatchers.IO) {
            // Récupération métadonnées
            metadata = AudioHelper.getAudioMetadata(currentFile)
            val meta = metadata
            
            if (meta == null) {
                withContext(Dispatchers.Main) { 
                    Toast.makeText(this@EditorActivity, "Erreur de lecture", Toast.LENGTH_SHORT).show()
                    finish() 
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                binding.txtDuration.text = formatTime(meta.duration)
                val estimatedPoints = (meta.duration / 1000) * AudioHelper.POINTS_PER_SECOND
                binding.waveformView.initialize(estimatedPoints)
            }

            // ✅ Streaming optimisé avec cache
            AudioHelper.loadWaveformStreamOptimized(
                currentFile,
                onUpdate = { newChunk ->
                    runOnUiThread {
                        binding.waveformView.appendData(newChunk)
                    }
                },
                onComplete = {
                    runOnUiThread {
                        binding.progressBar.visibility = View.GONE
                        // Feedback visuel subtil
                        Toast.makeText(
                            this@EditorActivity, 
                            "Waveform chargée ✓", 
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )
        }
    }

    /**
     * ⚡ PHASE 2 : Chargement PCM en arrière-plan (lazy)
     * L'utilisateur peut déjà naviguer/zoomer/lire pendant ce temps
     */
    private fun startBackgroundPcmLoading() {
        pcmLoadingJob = lifecycleScope.launch(Dispatchers.IO) {
            // Petit délai pour laisser l'UI se stabiliser
            delay(300)
            
            try {
                val content = AudioHelper.decodeToPCMLazy(currentFile)
                workingPcm = content.data
                sampleRate = content.sampleRate
                
                withContext(Dispatchers.Main) {
                    updateEditButtons(true)
                    // Toast discret pour informer
                    Toast.makeText(
                        this@EditorActivity, 
                        "Édition prête ✓", 
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@EditorActivity, 
                        "Erreur chargement PCM", 
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun updateEditButtons(enabled: Boolean) {
        isPcmReady = enabled
        val alpha = if (enabled) 1.0f else 0.5f
        
        binding.btnCut.isEnabled = enabled
        binding.btnCut.alpha = alpha
        binding.btnNormalize.isEnabled = enabled
        binding.btnNormalize.alpha = alpha
        binding.btnSave.isEnabled = enabled
        binding.btnSave.alpha = alpha
    }

    private fun updateCurrentTimeDisplay(index: Int) {
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

    /**
     * ✅ Coupe avec vérification du PCM
     */
    private fun cutSelection() {
        // Vérifier que le PCM est prêt
        if (!isPcmReady) {
            Toast.makeText(this, "Chargement en cours...", Toast.LENGTH_SHORT).show()
            return
        }
        
        val pcm = workingPcm ?: return
        val startIdx = binding.waveformView.selectionStart
        val endIdx = binding.waveformView.selectionEnd
        
        if (startIdx < 0 || endIdx <= startIdx) {
            Toast.makeText(this, "Sélectionnez une zone", Toast.LENGTH_SHORT).show()
            return
        }

        stopAudio()
        
        // Afficher progression
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = true
        
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                // Calcul physique
                val samplesPerPoint = sampleRate / AudioHelper.POINTS_PER_SECOND
                val startS = startIdx * samplesPerPoint
                val endS = (endIdx * samplesPerPoint).coerceAtMost(pcm.size)
                
                val newPcm = ShortArray(pcm.size - (endS - startS))
                System.arraycopy(pcm, 0, newPcm, 0, startS)
                System.arraycopy(pcm, endS, newPcm, startS, pcm.size - endS)
                
                workingPcm = newPcm
                
                // Régénération waveform
                val newWaveform = AudioHelper.generateWaveformFromPCM(newPcm, sampleRate)
                
                withContext(Dispatchers.Main) {
                    binding.waveformView.clearData()
                    binding.waveformView.appendData(newWaveform)
                    
                    // Update durée
                    val newMs = (newPcm.size * 1000L) / sampleRate
                    binding.txtDuration.text = formatTime(newMs)
                    
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@EditorActivity, "Coupé ✓", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@EditorActivity, "Erreur", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * ✅ Sauvegarde avec vérification
     */
    private fun saveChanges() {
        if (!isPcmReady) {
            Toast.makeText(this, "Chargement en cours...", Toast.LENGTH_SHORT).show()
            return
        }
        
        val pcm = workingPcm ?: return
        
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = true

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tmp = File(currentFile.parent, "tmp_save.m4a")
                val success = AudioHelper.savePCMToAAC(pcm, tmp, sampleRate)
                
                if (success) {
                    currentFile.delete()
                    tmp.renameTo(currentFile)
                    
                    // ✅ Invalider le cache pour forcer le reload
                    AudioHelper.invalidateCache(currentFile.absolutePath)
                    
                    withContext(Dispatchers.Main) {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(this@EditorActivity, "Enregistré ✓", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(this@EditorActivity, "Erreur sauvegarde", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@EditorActivity, "Erreur", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * ✅ Normalisation avec vérification
     */
    private fun normalizeSelection() {
        if (!isPcmReady) {
            Toast.makeText(this, "Chargement en cours...", Toast.LENGTH_SHORT).show()
            return
        }
        
        val meta = metadata ?: return
        val pcm = workingPcm ?: return
        
        stopAudio()
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = true
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val startIdx = if(binding.waveformView.selectionStart >= 0) 
                    binding.waveformView.selectionStart else 0
                val endIdx = if(binding.waveformView.selectionEnd > startIdx) 
                    binding.waveformView.selectionEnd else Int.MAX_VALUE
                
                val startMs = (startIdx * 1000L) / AudioHelper.POINTS_PER_SECOND
                val endMs = if(endIdx == Int.MAX_VALUE) meta.duration 
                    else (endIdx * 1000L) / AudioHelper.POINTS_PER_SECOND
                
                // Normalisation sur PCM en mémoire
                val startS = ((startMs * sampleRate) / 1000).toInt()
                val endS = ((endMs * sampleRate) / 1000).toInt().coerceAtMost(pcm.size)
                
                var maxVal = 0f
                for (i in startS until endS) {
                    val v = abs(pcm[i].toFloat() / 32768f)
                    if (v > maxVal) maxVal = v
                }
                
                if (maxVal > 0f) {
                    val gain = 0.95f / maxVal
                    for (i in startS until endS) {
                        val newVal = (pcm[i] * gain).toInt().coerceIn(-32768, 32767)
                        pcm[i] = newVal.toShort()
                    }
                    
                    // Régénération waveform
                    val newWaveform = AudioHelper.generateWaveformFromPCM(pcm, sampleRate)
                    
                    withContext(Dispatchers.Main) {
                        binding.waveformView.clearData()
                        binding.waveformView.appendData(newWaveform)
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(this@EditorActivity, "Normalisé ✓", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(this@EditorActivity, "Aucun son détecté", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@EditorActivity, "Erreur", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    override fun onStop() {
        super.onStop()
        stopAudio()
        pcmLoadingJob?.cancel()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        pcmLoadingJob?.cancel()
    }
}