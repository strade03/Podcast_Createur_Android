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

    private var currentPcmData: ShortArray? = null // Le son "vivant" en mémoire
    private var originalSampleRate: Int = 44100
    
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
            val pcm = currentPcmData ?: return@setOnClickListener
            binding.progressBar.visibility = View.VISIBLE
            
            lifecycleScope.launch(Dispatchers.IO) {
                val tmpFile = File(currentFile.parent, "save_tmp.m4a")
                val success = AudioHelper.savePCMToAAC(pcm, tmpFile, originalSampleRate)
                
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    if (success) {
                        currentFile.delete()
                        tmpFile.renameTo(currentFile)
                        Toast.makeText(this@EditorActivity, "Sauvegardé avec succès", Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        Toast.makeText(this@EditorActivity, "Erreur de sauvegarde", Toast.LENGTH_SHORT).show()
                    }
                }
            }
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

    // private fun loadWaveformStreaming() {
    //     binding.progressBar.visibility = View.VISIBLE
        
    //     lifecycleScope.launch(Dispatchers.IO) {
    //         metadata = AudioHelper.getAudioMetadata(currentFile)
    //         val meta = metadata
            
    //         if (meta == null) {
    //             withContext(Dispatchers.Main) { finish() }
    //             return@launch
    //         }

    //         withContext(Dispatchers.Main) {
    //             binding.txtDuration.text = formatTime(meta.duration)
    //             // On estime le nombre de points : Durée (sec) * 50
    //             val estimatedPoints = (meta.duration / 1000) * AudioHelper.POINTS_PER_SECOND
    //             binding.waveformView.initialize(estimatedPoints)
    //         }

    //         AudioHelper.loadWaveformStream(currentFile) { newChunk ->
    //             runOnUiThread {
    //                 binding.waveformView.appendData(newChunk)
    //                 if (binding.progressBar.visibility == View.VISIBLE) {
    //                     binding.progressBar.visibility = View.GONE
    //                 }
    //             }
    //         }
    //     }
    // }

    private fun loadWaveformStreaming() {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            // 1. Décodage complet en PCM
            val content = AudioHelper.decodeToPCM(currentFile)
            currentPcmData = content.data
            originalSampleRate = content.sampleRate
            metadata = AudioHelper.getAudioMetadata(currentFile)

            withContext(Dispatchers.Main) {
                // 2. Génération de l'onde à partir du PCM chargé
                val pcm = currentPcmData ?: return@withContext
                binding.txtDuration.text = formatTime((pcm.size.toLong() * 1000) / originalSampleRate)
                
                // Génération des points de l'onde (50 points par seconde)
                val samplesPerPoint = originalSampleRate / AudioHelper.POINTS_PER_SECOND
                val newPoints = FloatArray(pcm.size / samplesPerPoint)
                for (i in newPoints.indices) {
                    var max = 0f
                    for (j in 0 until samplesPerPoint) {
                        val idx = i * samplesPerPoint + j
                        if (idx < pcm.size) {
                            val v = Math.abs(pcm[idx].toFloat() / 32768f)
                            if (v > max) max = v
                        }
                    }
                    newPoints[i] = max
                }
                
                binding.waveformView.clearData()
                binding.waveformView.initialize(newPoints.size.toLong())
                binding.waveformView.appendData(newPoints)
                binding.progressBar.visibility = View.GONE
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
        val pcm = currentPcmData ?: return
        val startIdx = binding.waveformView.selectionStart
        val endIdx = binding.waveformView.selectionEnd
        if (startIdx < 0 || endIdx <= startIdx) return

        stopAudio()
        
        // Conversion Index Waveform -> Samples PCM
        val samplesPerPoint = originalSampleRate / AudioHelper.POINTS_PER_SECOND
        val startSample = startIdx * samplesPerPoint
        val endSample = endIdx * samplesPerPoint

        // COUPE MÉMOIRE (Ultra rapide)
        currentPcmData = AudioHelper.cutShortArray(pcm, startSample, endSample)

        // Mise à jour de l'interface
        val newPointsSize = currentPcmData!!.size / samplesPerPoint
        // Ici on pourrait recalculer l'onde ou simplement retirer les points du WaveformView
        // Pour faire simple, on recharge l'affichage à partir du nouveau PCM
        refreshWaveformFromPcm() 
        Toast.makeText(this, "Coupé !", Toast.LENGTH_SHORT).show()
    }

    private fun refreshWaveformFromPcm() {
        val pcm = currentPcmData ?: return
        
        // 1. Calculer le nombre d'échantillons par point (pour garder 50 points par seconde)
        val samplesPerPoint = originalSampleRate / AudioHelper.POINTS_PER_SECOND
        
        // 2. Éviter la division par zéro si le fichier est trop court
        if (samplesPerPoint == 0) return 

        val newPointsSize = pcm.size / samplesPerPoint
        val newPoints = FloatArray(newPointsSize)

        // 3. Calculer les pics (comme lors du chargement initial)
        for (i in 0 until newPointsSize) {
            var max = 0f
            for (j in 0 until samplesPerPoint) {
                val idx = i * samplesPerPoint + j
                if (idx < pcm.size) {
                    val v = Math.abs(pcm[idx].toFloat() / 32768f)
                    if (v > max) max = v
                }
            }
            newPoints[i] = max
        }

        // 4. Mettre à jour la vue et les textes
        binding.waveformView.clearData()
        binding.waveformView.initialize(newPointsSize.toLong())
        binding.waveformView.appendData(newPoints)
        
        // Mettre à jour le texte de la durée totale en haut
        val totalMs = (pcm.size.toLong() * 1000) / originalSampleRate
        binding.txtDuration.text = formatTime(totalMs)
        
        // Replacer le temps courant à 0 ou au début de la sélection
        updateCurrentTimeDisplay(binding.waveformView.playheadPos)
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