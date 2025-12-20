package com.podcastcreateur.app

import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.podcastcreateur.app.databinding.ActivityEditorBinding
import kotlinx.coroutines.*
import java.io.File

/**
 * ÉDITEUR OPTIMISÉ - VERSION CORRIGÉE
 */
class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var currentFile: File
    
    private var waveformData: FloatArray = FloatArray(0)
    private var metadata: AudioMetadata? = null
    
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
            Toast.makeText(this, "Modifications enregistrées", Toast.LENGTH_SHORT).show()
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

    private fun loadWaveformOptimized() {
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                metadata = AudioHelper.getAudioMetadata(currentFile)
                
                if (metadata == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@EditorActivity, "Erreur lecture fichier", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    return@launch
                }
                
                val screenWidth = resources.displayMetrics.widthPixels
                val targetWidth = screenWidth * 2
                
                waveformData = AudioHelper.generateWaveformData(currentFile, targetWidth)
                
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
     * LECTURE CORRIGÉE - Mise à jour du pointeur et bouton
     */
    private fun playAudio() {
        val meta = metadata ?: return
        if (isPlaying) return
        
        isPlaying = true
        binding.btnPlay.setImageResource(R.drawable.ic_stop_read)

        playbackJob = lifecycleScope.launch(Dispatchers.IO) {
            var extractor: MediaExtractor? = null
            var decoder: MediaCodec? = null
            
            try {
                extractor = MediaExtractor()
                extractor.setDataSource(currentFile.absolutePath)
                
                var trackIndex = -1
                var format: MediaFormat? = null
                
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME)
                    if (mime?.startsWith("audio/") == true) {
                        trackIndex = i
                        format = f
                        break
                    }
                }
                
                if (trackIndex < 0 || format == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@EditorActivity, "Format audio non supporté", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                
                // Positionnement au début de la sélection si applicable
                var startSample = if (binding.waveformView.selectionStart >= 0) {
                    binding.waveformView.selectionStart
                } else {
                    binding.waveformView.playheadPos
                }
                
                val endSample = if (binding.waveformView.selectionEnd > startSample) {
                    binding.waveformView.selectionEnd
                } else {
                    meta.totalSamples.toInt()
                }
                
                // Convertir en temps
                val startMs = (startSample * 1000L) / meta.sampleRate
                extractor.selectTrack(trackIndex)
                extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                
                // Créer le décodeur
                val mime = format.getString(MediaFormat.KEY_MIME) ?: return@launch
                decoder = MediaCodec.createDecoderByType(mime)
                decoder.configure(format, null, null, 0)
                decoder.start()
                
                // Créer l'AudioTrack
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
                
                val bufferInfo = MediaCodec.BufferInfo()
                var currentSample = startSample
                val updateInterval = meta.sampleRate / 20 // 20 Hz
                var samplesSinceUpdate = 0
                var isInputDone = false
                
                // Boucle de décodage/lecture streaming
                while (isPlaying && currentSample < endSample) {
                    // Feed input
                    if (!isInputDone) {
                        val inIndex = decoder.dequeueInputBuffer(10000)
                        if (inIndex >= 0) {
                            val inBuffer = decoder.getInputBuffer(inIndex)
                            if (inBuffer != null) {
                                val sampleSize = extractor.readSampleData(inBuffer, 0)
                                val sampleTime = extractor.sampleTime / 1000 // µs -> ms
                                val sampleTimeMs = sampleTime
                                val endMs = (endSample * 1000L) / meta.sampleRate
                                
                                if (sampleSize < 0 || sampleTimeMs >= endMs) {
                                    decoder.queueInputBuffer(
                                        inIndex, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    isInputDone = true
                                } else {
                                    decoder.queueInputBuffer(
                                        inIndex, 0, sampleSize,
                                        extractor.sampleTime, 0
                                    )
                                    extractor.advance()
                                }
                            }
                        }
                    }
                    
                    // Get output
                    val outIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                    if (outIndex >= 0) {
                        val outBuffer = decoder.getOutputBuffer(outIndex)
                        if (outBuffer != null && bufferInfo.size > 0) {
                            // Lire les samples
                            val tempArray = ShortArray(bufferInfo.size / 2)
                            outBuffer.order(ByteOrder.LITTLE_ENDIAN)
                            outBuffer.asShortBuffer().get(tempArray)
                            
                            // Jouer l'audio
                            audioTrack?.write(tempArray, 0, tempArray.size)
                            
                            // Mettre à jour la position
                            currentSample += tempArray.size
                            samplesSinceUpdate += tempArray.size
                            
                            if (samplesSinceUpdate >= updateInterval) {
                                samplesSinceUpdate = 0
                                withContext(Dispatchers.Main) {
                                    binding.waveformView.playheadPos = currentSample.coerceIn(0, meta.totalSamples.toInt())
                                    binding.waveformView.invalidate()
                                    autoScroll(currentSample)
                                }
                            }
                        }
                        
                        decoder.releaseOutputBuffer(outIndex, false)
                        
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            break
                        }
                    } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (isInputDone) break
                    }
                }
                
                // Dernière mise à jour
                withContext(Dispatchers.Main) {
                    binding.waveformView.playheadPos = currentSample
                    binding.waveformView.invalidate()
                }
                
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@EditorActivity, "Erreur lecture: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                try {
                    audioTrack?.stop()
                    audioTrack?.release()
                    audioTrack = null
                    decoder?.stop()
                    decoder?.release()
                    extractor?.release()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                
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
        binding.scroller.smoothScrollTo(scrollX.coerceAtLeast(0), 0)
    }

    private fun stopAudio() {
        isPlaying = false
        playbackJob?.cancel()
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        
        // CORRECTION: Réinitialiser le bouton immédiatement
        binding.btnPlay.setImageResource(R.drawable.ic_play)
        binding.waveformView.invalidate()
    }
    
    /**
     * COUPE CORRIGÉE
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
            .setMessage("La partie sélectionnée sera supprimée")
            .setPositiveButton("Oui") { _, _ ->
                performCut(start, end, meta)
            }
            .setNegativeButton("Non", null)
            .show()
    }
    
    private fun performCut(startSample: Int, endSample: Int, meta: AudioMetadata) {
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Charger les données complètes (nécessaire pour la coupe)
                val content = AudioHelper.decodeToPCM(currentFile)
                val pcmData = content.data
                
                // CORRECTION: Créer un nouveau tableau sans la partie sélectionnée
                val beforeCut = pcmData.sliceArray(0 until startSample)
                val afterCut = pcmData.sliceArray(endSample until pcmData.size)
                val resultData = beforeCut + afterCut
                
                val tempFile = File(currentFile.parent, "temp_cut_${System.currentTimeMillis()}.m4a")
                
                // Sauvegarder le résultat
                val success = AudioHelper.savePCMToAAC(resultData, tempFile, meta.sampleRate)
                
                if (success) {
                    currentFile.delete()
                    tempFile.renameTo(currentFile)
                    
                    withContext(Dispatchers.Main) {
                        binding.waveformView.clearSelection()
                        binding.waveformView.playheadPos = 0
                        loadWaveformOptimized()
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