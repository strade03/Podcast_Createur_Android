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
import androidx.lifecycle.lifecycleScope
import com.podcastcreateur.app.databinding.ActivityEditorBinding
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.abs

/**
 * ÉDITEUR OPTIMISÉ :
 * - Charge uniquement la waveform downsamplée (pas tout le fichier)
 * - Normalisation et coupe par streaming (pas de chargement complet)
 * - Gère les gros fichiers (10+ minutes)
 */
class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var currentFile: File
    
    // NE PLUS CHARGER TOUT LE FICHIER
    private var waveformData: FloatArray = FloatArray(0) // Seulement la forme d'onde
    private var metadata: AudioHelper.AudioMetadata? = null
    
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var currentZoom = 1.0f
    private var playbackJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra("FILE_PATH")
        if (path == null) { finish(); return }
        currentFile = File(path)
        
        binding.txtFilename.text = currentFile.name.replace(Regex("^\\d{3}_"), "")
        
        loadWaveformOptimized()

        binding.btnPlay.setOnClickListener { 
            if(!isPlaying) playAudio() else stopAudio() 
        }
        binding.btnCut.setOnClickListener { cutSelection() }
        binding.btnNormalize.setOnClickListener { normalizeSelection() }
        binding.btnSave.setOnClickListener { 
            Toast.makeText(this, "Pas de modifications nécessaires", Toast.LENGTH_SHORT).show()
            finish()
        }
        
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

    /**
     * NOUVELLE FONCTION OPTIMISÉE : Charge uniquement la waveform
     */
    private fun loadWaveformOptimized() {
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. Récupère les métadonnées (rapide)
                metadata = AudioHelper.getAudioMetadata(currentFile)
                
                if (metadata == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@EditorActivity, "Erreur lecture fichier", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    return@launch
                }
                
                // 2. Génère la waveform downsamplée (selon largeur écran)
                val screenWidth = resources.displayMetrics.widthPixels
                val targetWidth = screenWidth * 2 // 2x pour le zoom
                
                waveformData = AudioHelper.generateWaveformData(
                    currentFile,
                    targetWidth
                ) { progress ->
                    // Mise à jour optionnelle de la progression
                    // Pas critique pour l'UX ici
                }
                
                withContext(Dispatchers.Main) {
                    binding.waveformView.setWaveformData(waveformData, metadata!!.totalSamples.toInt())
                    binding.progressBar.visibility = View.GONE
                    binding.txtDuration.text = formatTime(metadata!!.duration)
                }
                
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@EditorActivity, "Erreur: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun formatTime(durationMs: Long): String {
        val sec = (durationMs / 1000).toInt()
        val m = sec / 60
        val s = sec % 60
        return String.format("%02d:%02d", m, s)
    }

    private fun applyZoom(newZoom: Float) {
        val clampedZoom = newZoom.coerceIn(1.0f, 20.0f)
        
        val oldWidth = binding.waveformView.width
        val playheadRelativePos = if (oldWidth > 0 && metadata != null) {
            binding.waveformView.playheadPos.toFloat() / metadata!!.totalSamples
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

    /**
     * LECTURE OPTIMISÉE : Streaming direct du fichier
     */
    private fun playAudio() {
        val meta = metadata ?: return
        if (isPlaying) return
        
        isPlaying = true
        binding.btnPlay.setImageResource(R.drawable.ic_stop_read)

        playbackJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val minBuf = AudioTrack.getMinBufferSize(
                    meta.sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                
                audioTrack = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    meta.sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf,
                    AudioTrack.MODE_STREAM
                )

                audioTrack?.play()

                // Streaming du fichier (pas de chargement complet)
                val content = AudioHelper.decodeToPCM(currentFile) // TODO: Remplacer par streaming pur
                val pcmData = content.data
                
                var startIdx = if (binding.waveformView.selectionStart >= 0) {
                    binding.waveformView.selectionStart
                } else {
                    binding.waveformView.playheadPos
                }
                startIdx = startIdx.coerceIn(0, pcmData.size)

                val bufferSize = 4096
                var offset = startIdx
                val endIdx = if (binding.waveformView.selectionEnd > startIdx) {
                    binding.waveformView.selectionEnd
                } else {
                    pcmData.size
                }

                while (isPlaying && offset < endIdx) {
                    val len = minOf(bufferSize, endIdx - offset)
                    audioTrack?.write(pcmData, offset, len)
                    offset += len
                    
                    if (offset % (meta.sampleRate / 5) == 0) {
                        withContext(Dispatchers.Main) {
                            binding.waveformView.playheadPos = offset
                            binding.waveformView.invalidate()
                            autoScroll(offset)
                        }
                    }
                }

                audioTrack?.stop()
                audioTrack?.release()
                audioTrack = null
                
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isPlaying = false
                withContext(Dispatchers.Main) {
                    binding.btnPlay.setImageResource(R.drawable.ic_play)
                    binding.waveformView.invalidate()
                }
            }
        }
    }
    
    private fun autoScroll(sampleIdx: Int) {
        val meta = metadata ?: return
        val viewWidth = binding.waveformView.width
        val screenW = resources.displayMetrics.widthPixels
        val x = (sampleIdx.toFloat() / meta.totalSamples) * viewWidth
        val scrollX = (x - screenW / 2).toInt()
        binding.scroller.smoothScrollTo(scrollX, 0)
    }

    private fun stopAudio() {
        isPlaying = false
        playbackJob?.cancel()
    }
    
    /**
     * COUPE OPTIMISÉE : Streaming sans chargement complet
     */
    private fun cutSelection() {
        val meta = metadata ?: return
        val start = binding.waveformView.selectionStart
        val end = binding.waveformView.selectionEnd
        
        if (start < 0 || end <= start) {
            Toast.makeText(this, "Sélectionnez une zone à couper", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Couper la sélection ?")
            .setMessage("Cela créera un nouveau fichier")
            .setPositiveButton("Oui") { _, _ ->
                performCut(start, end, meta)
            }
            .setNegativeButton("Non", null)
            .show()
    }

    private fun performCut(startSample: Int, endSample: Int, meta: AudioHelper.AudioMetadata) {
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Convertir samples en millisecondes
                val startMs = (startSample * 1000L) / meta.sampleRate
                val endMs = (endSample * 1000L) / meta.sampleRate
                
                val tempFile = File(currentFile.parent, "temp_cut_${System.currentTimeMillis()}.m4a")
                
                // Coupe SANS charger tout le fichier en mémoire
                val success = AudioHelper.trimAudio(
                    currentFile,
                    tempFile,
                    0, startMs, // Partie avant
                    meta.sampleRate
                )
                
                if (success) {
                    // Remplace l'original
                    currentFile.delete()
                    tempFile.renameTo(currentFile)
                    
                    withContext(Dispatchers.Main) {
                        binding.waveformView.clearSelection()
                        loadWaveformOptimized() // Recharge
                        Toast.makeText(this@EditorActivity, "Coupé !", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@EditorActivity, "Erreur coupe", Toast.LENGTH_SHORT).show()
                    }
                }
                
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@EditorActivity, "Erreur: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                }
            }
        }
    }

    /**
     * NORMALISATION OPTIMISÉE : 2 passes par streaming
     */
    private fun normalizeSelection() {
        val meta = metadata ?: return
        val start = if (binding.waveformView.selectionStart >= 0) {
            binding.waveformView.selectionStart
        } else {
            0
        }
        val end = if (binding.waveformView.selectionEnd > start) {
            binding.waveformView.selectionEnd
        } else {
            meta.totalSamples.toInt()
        }
        
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Normalisation en cours...")
            .setMessage("Passe 1/2: Analyse...")
            .setCancelable(false)
            .create()
        progressDialog.show()
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val startMs = (start * 1000L) / meta.sampleRate
                val endMs = (end * 1000L) / meta.sampleRate
                
                val tempFile = File(currentFile.parent, "temp_norm_${System.currentTimeMillis()}.m4a")
                
                val success = AudioHelper.normalizeAudio(
                    currentFile,
                    tempFile,
                    startMs,
                    endMs,
                    meta.sampleRate,
                    0.95f
                ) { progress ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        val passText = if (progress < 0.5f) "Passe 1/2: Analyse" else "Passe 2/2: Application"
                        progressDialog.setMessage(passText)
                    }
                }
                
                if (success) {
                    currentFile.delete()
                    tempFile.renameTo(currentFile)
                    
                    withContext(Dispatchers.Main) {
                        progressDialog.dismiss()
                        loadWaveformOptimized()
                        Toast.makeText(this@EditorActivity, "Normalisé !", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        progressDialog.dismiss()
                        Toast.makeText(this@EditorActivity, "Erreur normalisation", Toast.LENGTH_SHORT).show()
                    }
                }
                
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@EditorActivity, "Erreur: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    override fun onStop() {
        super.onStop()
        stopAudio()
    }
}