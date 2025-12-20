package com.podcastcreateur.app

import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.podcastcreateur.app.databinding.ActivityEditorBinding
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * ÉDITEUR : Chargement Streaming et affichage Peak (type Audacity)
 */
class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var currentFile: File
    
    // On ne stocke plus tout ici, la vue gère ses données
    private var metadata: AudioMetadata? = null
    
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var playbackJob: Job? = null
    
    // Zoom par défaut élevé pour voir les détails (type "1 minute sur l'écran")
    private var currentZoom = 10.0f 

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra("FILE_PATH")
        if (path == null) { finish(); return }
        currentFile = File(path)
        
        binding.txtFilename.text = currentFile.name.replace(Regex("^\\d{3}_"), "")
        
        // Initialisation de la vue avec Zoom
        binding.waveformView.setZoomLevel(currentZoom)
        
        loadWaveformStreaming()

        binding.btnPlay.setOnClickListener { 
            if(!isPlaying) playAudio() else stopAudio() 
        }
        binding.btnCut.setOnClickListener { cutSelection() }
        binding.btnNormalize.setOnClickListener { normalizeSelection() }
        binding.btnSave.setOnClickListener { 
            Toast.makeText(this, "Sauvegardé", Toast.LENGTH_SHORT).show()
            finish()
        }
        
        binding.btnZoomIn.setOnClickListener { applyZoom(currentZoom * 1.5f) }
        binding.btnZoomOut.setOnClickListener { applyZoom(currentZoom / 1.5f) }
        
        binding.btnReRecord.setOnClickListener {
            // Logique re-record inchangée
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

    private fun loadWaveformStreaming() {
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch(Dispatchers.IO) {
            // 1. Métadonnées (Rapide)
            metadata = AudioHelper.getAudioMetadata(currentFile)
            val meta = metadata
            
            if (meta == null) {
                withContext(Dispatchers.Main) { finish() }
                return@launch
            }

            withContext(Dispatchers.Main) {
                binding.txtDuration.text = formatTime(meta.duration)
                // On informe la vue de la durée totale estimée pour gérer le scroll
                binding.waveformView.initialize(meta.totalSamples)
            }

            // 2. Chargement Streaming (L'onde apparait petit à petit)
            AudioHelper.loadWaveformStream(currentFile) { newChunk ->
                // Mise à jour UI thread
                runOnUiThread {
                    binding.waveformView.appendData(newChunk)
                    // Si c'est le premier chunk, on cache la progress bar
                    if (binding.progressBar.visibility == View.VISIBLE) {
                        binding.progressBar.visibility = View.GONE
                    }
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
        // Limites de zoom plus larges
        val clamped = newZoom.coerceIn(0.5f, 50.0f)
        currentZoom = clamped
        
        val meta = metadata ?: return
        // Garder le curseur centré
        val centerSample = binding.waveformView.getCenterSample(binding.scroller.scrollX, binding.scroller.width)
        
        binding.waveformView.setZoomLevel(currentZoom)
        
        binding.waveformView.post {
            val newScrollX = binding.waveformView.sampleToPixel(centerSample) - (binding.scroller.width / 2)
            binding.scroller.scrollTo(newScrollX.toInt().coerceAtLeast(0), 0)
        }
    }

    private fun playAudio() {
        val meta = metadata ?: return
        if (isPlaying) return
        isPlaying = true
        binding.btnPlay.setImageResource(R.drawable.ic_stop_read)

        playbackJob = lifecycleScope.launch(Dispatchers.IO) {
            var extractor: MediaExtractor? = null
            var decoder: MediaCodec? = null
            var track: AudioTrack? = null
            
            try {
                extractor = MediaExtractor()
                extractor.setDataSource(currentFile.absolutePath)
                var idx = -1
                for(i in 0 until extractor.trackCount) {
                    if(extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true) {
                        idx = i; break
                    }
                }
                if(idx < 0) return@launch
                
                extractor.selectTrack(idx)
                val format = extractor.getTrackFormat(idx)
                
                // Début de lecture (Sélection ou Playhead)
                val startSample = if(binding.waveformView.selectionStart >= 0) binding.waveformView.selectionStart else binding.waveformView.playheadPos
                val endSample = if(binding.waveformView.selectionEnd > startSample) binding.waveformView.selectionEnd else meta.totalSamples.toInt()
                
                val startMs = (startSample * 1000L) / meta.sampleRate
                extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                
                val mime = format.getString(MediaFormat.KEY_MIME)!!
                decoder = MediaCodec.createDecoderByType(mime)
                decoder.configure(format, null, null, 0)
                decoder.start()
                
                val minBuf = AudioTrack.getMinBufferSize(meta.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                track = AudioTrack(AudioManager.STREAM_MUSIC, meta.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf, AudioTrack.MODE_STREAM)
                track.play()
                audioTrack = track
                
                val info = MediaCodec.BufferInfo()
                var currentS = startSample
                var isEOS = false
                
                while(isActive && isPlaying && currentS < endSample) {
                    if(!isEOS) {
                        val inIdx = decoder.dequeueInputBuffer(5000)
                        if(inIdx >= 0) {
                            val buf = decoder.getInputBuffer(inIdx)
                            val sz = extractor.readSampleData(buf!!, 0)
                            if(sz < 0 || (extractor.sampleTime/1000) > (endSample*1000L/meta.sampleRate)) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isEOS = true
                            } else {
                                decoder.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    
                    val outIdx = decoder.dequeueOutputBuffer(info, 5000)
                    if(outIdx >= 0) {
                        val outBuf = decoder.getOutputBuffer(outIdx)
                        if(outBuf != null && info.size > 0) {
                            val chunk = ByteArray(info.size)
                            outBuf.get(chunk)
                            track.write(chunk, 0, chunk.size)
                            
                            // Conversion approximative bytes -> samples (16bit mono = 2 bytes)
                            val samplesRead = chunk.size / 2
                            currentS += samplesRead
                            
                            withContext(Dispatchers.Main) {
                                binding.waveformView.playheadPos = currentS
                                binding.waveformView.invalidate()
                                autoScroll(currentS)
                            }
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                        if(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            } catch(e: Exception) { e.printStackTrace() }
            finally {
                try { track?.stop(); track?.release(); decoder?.release(); extractor?.release() } catch(e:Exception){}
                isPlaying = false
                withContext(Dispatchers.Main) {
                    binding.btnPlay.setImageResource(R.drawable.ic_play)
                }
            }
        }
    }

    private fun autoScroll(sampleIdx: Int) {
        val px = binding.waveformView.sampleToPixel(sampleIdx)
        val screenCenter = binding.scroller.width / 2
        val target = (px - screenCenter).toInt().coerceAtLeast(0)
        // Scroll doux seulement si on est loin
        if (abs(binding.scroller.scrollX - target) > 10) {
            binding.scroller.scrollTo(target, 0)
        }
    }

    private fun stopAudio() {
        isPlaying = false
        playbackJob?.cancel()
        audioTrack?.pause()
        audioTrack?.flush()
        binding.btnPlay.setImageResource(R.drawable.ic_play)
    }

    private fun cutSelection() {
        // Idem version précédente, reload complet après cut
        val meta = metadata ?: return
        val start = binding.waveformView.selectionStart
        val end = binding.waveformView.selectionEnd
        if (start < 0 || end <= start) return
        
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            // ... Logique de coupe identique à avant ...
            // Simplification ici : on recharge tout après la coupe
            val content = AudioHelper.decodeToPCM(currentFile)
            val pcm = content.data
            val kept = pcm.sliceArray(0 until start) + pcm.sliceArray(end until pcm.size)
            val tmp = File(currentFile.parent, "tmp.m4a")
            if(AudioHelper.savePCMToAAC(kept, tmp, meta.sampleRate)) {
                currentFile.delete(); tmp.renameTo(currentFile)
                withContext(Dispatchers.Main) {
                    binding.waveformView.clearData()
                    loadWaveformStreaming()
                    Toast.makeText(this@EditorActivity, "Coupé", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun normalizeSelection() {
        val meta = metadata ?: return
        val start = if(binding.waveformView.selectionStart >= 0) binding.waveformView.selectionStart else 0
        val end = if(binding.waveformView.selectionEnd > start) binding.waveformView.selectionEnd else meta.totalSamples.toInt()
        
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val tmp = File(currentFile.parent, "tmp_norm.m4a")
            val sMs = (start * 1000L)/meta.sampleRate
            val eMs = (end * 1000L)/meta.sampleRate
            if(AudioHelper.normalizeAudio(currentFile, tmp, sMs, eMs, meta.sampleRate, 0.95f) {}) {
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