package com.podcastcreateur.app

import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.podcastcreateur.app.databinding.ActivityEditorBinding
import com.linc.amplituda.Amplituda
import com.linc.amplituda.Compress
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.abs

class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var currentFile: File
    
    private var sampleRate = 44100
    private var totalDurationMs = 0L

    private var mediaPlayer: MediaPlayer? = null
    private var playbackJob: Job? = null
    
    private var currentZoom = 1.0f 
    private lateinit var amplituda: Amplituda

    private val pendingCuts = ArrayList<Pair<Long, Long>>() 
    
    private var pendingGain = 1.0f
    private var msPerPoint: Double = 20.0 

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        amplituda = Amplituda(this)

        val path = intent.getStringExtra("FILE_PATH")
        if (path == null) { finish(); return }
        currentFile = File(path)
        
        binding.txtFilename.text = currentFile.name.replace(Regex("^\\d{3}_"), "")
        
        loadWaveformFast()

        binding.waveformView.onPositionChanged = { index -> updateCurrentTimeDisplay(index) }

        binding.btnPlay.setOnClickListener { 
            if(mediaPlayer?.isPlaying == true) stopAudio() else playAudio() 
        }
        
        binding.btnCut.setOnClickListener { performVirtualCut() }
        binding.btnNormalize.setOnClickListener { prepareVirtualNormalization() } 
        binding.btnSave.setOnClickListener { saveChangesAndExit() }
        
        binding.btnZoomIn.setOnClickListener { applyZoom(currentZoom * 1.5f) }
        binding.btnZoomOut.setOnClickListener { applyZoom(currentZoom / 1.5f) }
        
        binding.btnReRecord.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.confirm_re_record))
                .setPositiveButton(getString(R.string.btn_yes)) { _, _ ->
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
            }.setNegativeButton(getString(R.string.btn_no), null).show()
        }
    }

    private fun loadWaveformFast() {
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch(Dispatchers.IO) {
            val meta = AudioHelper.getAudioMetadata(currentFile)
            if (meta != null) {
                sampleRate = meta.sampleRate
                totalDurationMs = meta.duration
            }
            
            // GARDE LA LOGIQUE BASE : Meilleure résolution
            val durationSec = (totalDurationMs / 1000).coerceAtLeast(1)
            // val requestPps = when {
            //     durationSec < 10 -> 5000
            //     durationSec < 60 -> 2000 
            //     durationSec < 300 -> 400
            //     durationSec < 900 -> 100
            //     else -> 50 
            // }
            val requestPps = when {
                durationSec < 10 -> 200
                durationSec < 60 -> 100 
                durationSec < 300 -> 80
                durationSec < 900 -> 50
                else -> 30 
            }

            amplituda.processAudio(
                currentFile.absolutePath, 
                Compress.withParams(Compress.AVERAGE, requestPps) 
            ).get({ result ->
               if (totalDurationMs <= 0) {
                    try {
                        // Utilisation de l'API demandée
                        totalDurationMs = result.getAudioDuration(com.linc.amplituda.AmplitudaResult.DurationUnit.MILLIS)
                    } catch (e: Exception) {
                        e.printStackTrace() // Si même Amplituda échoue
                    }
                }
                val amplitudes = result.amplitudesAsList()
                
                if (amplitudes.isNotEmpty() && totalDurationMs > 0) {
                    msPerPoint = totalDurationMs.toDouble() / amplitudes.size.toDouble()
                }
                } else {
                    // Cas désespéré : on met une valeur par défaut pour éviter crash division par 0
                    msPerPoint = 100.0 
                }                

                val maxVal = amplitudes.maxOrNull() ?: 1
                val floats = FloatArray(amplitudes.size)
                for (i in amplitudes.indices) {
                    floats[i] = amplitudes[i].toFloat() / maxVal.toFloat() 
                }

                runOnUiThread {
                     if (isDestroyed || isFinishing) return@runOnUiThread
                    binding.txtDuration.text = formatTime(totalDurationMs)
                    binding.waveformView.initialize(floats.size.toLong())
                    binding.waveformView.appendData(floats)
                    
                    binding.scroller.post {
                        val screenWidth = binding.scroller.width
                        if (screenWidth > 0 && floats.isNotEmpty()) {
                            val fitZoom = screenWidth.toFloat() / floats.size.toFloat()
                            applyZoom(fitZoom.coerceAtLeast(0.5f))
                        }
                    }
                    binding.progressBar.visibility = View.GONE
                }
            }, { error ->
                
                android.util.Log.e("Amplituda", "Erreur: ${error.message}", error)
                // error.printStackTrace()
                runOnUiThread { 
                    binding.progressBar.visibility = View.GONE
                    if (binding.waveformView.getPointsCount() == 0) {
                        Toast.makeText(this@EditorActivity, getString(R.string.error_file_read), Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }
    }

    private fun performVirtualCut() {
        val startIdx = binding.waveformView.selectionStart
        val endIdx = binding.waveformView.selectionEnd
        if (startIdx < 0 || endIdx <= startIdx) return

        stopAudio()
        
        val visualStartMs = (startIdx * msPerPoint).toLong()
        val visualEndMs = (endIdx * msPerPoint).toLong()
        val durationCut = visualEndMs - visualStartMs
        
        val realStartMs = mapVisualToRealTime(visualStartMs)
        val realEndMs = realStartMs + durationCut
        
        pendingCuts.add(Pair(realStartMs, realEndMs))
        binding.waveformView.deleteRange(startIdx, endIdx)
        
        var totalCutMs = 0L
        pendingCuts.forEach { totalCutMs += (it.second - it.first) }
        val displayDuration = totalDurationMs - totalCutMs
        
        binding.txtDuration.text = formatTime(displayDuration)
    }
    
    private fun mapVisualToRealTime(visualMs: Long): Long {
        var realMs = visualMs
        val sortedCuts = pendingCuts.sortedBy { it.first }
        for (cut in sortedCuts) {
            if (cut.first < realMs) {
                 realMs += (cut.second - cut.first)
            }
        }
        return realMs
    }

    private fun prepareVirtualNormalization() {
        stopAudio()
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch(Dispatchers.IO) {
            val peak = AudioHelper.calculatePeak(currentFile)
            
            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.GONE
                if (peak > 0.01f) {
                    val target = 0.98f
                    pendingGain = target / peak
                    Toast.makeText(this@EditorActivity, getString(R.string.normalization_ready), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@EditorActivity, getString(R.string.audio_too_quiet), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveChangesAndExit() {
        if (pendingCuts.isEmpty() && pendingGain == 1.0f) { finish(); return }
        
        stopAudio()
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch(Dispatchers.IO) {
            // Utilisation du nom temporaire (pratique Optimisée) mais logique AudioHelper de Base
            val tmpFile = File(currentFile.parent, "tmp_save_final.m4a")
            val samplesCuts = ArrayList<Pair<Long, Long>>()
            val meta = AudioHelper.getAudioMetadata(currentFile) ?: return@launch
            
            val samplesPerMs = (meta.sampleRate * meta.channelCount) / 1000.0
            
            for (cut in pendingCuts) {
                val sStart = (cut.first * samplesPerMs).toLong()
                val sEnd = (cut.second * samplesPerMs).toLong()
                samplesCuts.add(Pair(sStart, sEnd))
            }
            
            val success = AudioHelper.saveWithCutsAndGain(currentFile, tmpFile, samplesCuts, pendingGain)
            
            withContext(Dispatchers.Main) {
                if (success) {
                    currentFile.delete()
                    tmpFile.renameTo(currentFile)
                    Toast.makeText(this@EditorActivity, getString(R.string.saved_success), Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this@EditorActivity, getString(R.string.error_save), Toast.LENGTH_SHORT).show()
                    binding.progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun updateCurrentTimeDisplay(index: Int) {
        val visualMs = (index * msPerPoint).toLong()
        binding.txtCurrentTime.text = formatTime(visualMs)
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
        binding.waveformView.setZoomLevel(currentZoom)
    }

    private fun playAudio() {
        stopAudio()
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(currentFile.absolutePath)
                if (pendingGain != 1.0f) {
                    val vol = pendingGain.coerceAtMost(1.0f)
                    setVolume(vol, vol)
                }
                prepare()
                
                val startIdx = if(binding.waveformView.selectionStart >= 0) binding.waveformView.selectionStart else binding.waveformView.playheadPos
                val visualStartMs = (startIdx * msPerPoint).toLong()
                val realStartMs = mapVisualToRealTime(visualStartMs)
                
                seekTo(realStartMs.toInt())
                start()
                setOnCompletionListener { stopAudio() }
            }
            binding.btnPlay.setImageResource(R.drawable.ic_stop_read)
            
            playbackJob = lifecycleScope.launch {
                val sortedCuts = pendingCuts.sortedBy { it.first }
                while (mediaPlayer?.isPlaying == true) {
                    val currentRealMs = mediaPlayer?.currentPosition?.toLong() ?: 0L
                    
                    var inCut = false
                    for (cut in sortedCuts) {
                        if (currentRealMs >= cut.first && currentRealMs < cut.second) {
                            mediaPlayer?.seekTo(cut.second.toInt())
                            inCut = true
                            break
                        }
                    }
                    
                    if (!inCut) {
                        var visualMs = currentRealMs
                        for (cut in sortedCuts) {
                            if (currentRealMs > cut.second) {
                                visualMs -= (cut.second - cut.first)
                            }
                        }
                        
                        val currentIdx = (visualMs / msPerPoint).toInt()
                        
                        binding.waveformView.playheadPos = currentIdx
                        binding.waveformView.invalidate()
                        runOnUiThread { updateCurrentTimeDisplay(currentIdx) }
                        autoScroll(currentIdx)
                        
                        if (binding.waveformView.selectionStart >= 0 && 
                            binding.waveformView.selectionEnd > binding.waveformView.selectionStart && 
                            currentIdx >= binding.waveformView.selectionEnd) {
                            mediaPlayer?.pause()
                            break
                        }
                    }
                    delay(30)
                }
                if (mediaPlayer?.isPlaying != true) stopAudio()
            }
        } catch (e: Exception) { 
            e.printStackTrace()
            Toast.makeText(this, getString(R.string.error_audio_play), Toast.LENGTH_SHORT).show()
        }
    }

    private fun autoScroll(sampleIdx: Int) {
        val px = binding.waveformView.sampleToPixel(sampleIdx)
        val screenCenter = binding.scroller.width / 2
        val target = (px - screenCenter).toInt().coerceAtLeast(0)
        if (abs(binding.scroller.scrollX - target) > 50) {
            binding.scroller.smoothScrollTo(target, 0)
        }
    }

    private fun stopAudio() {
        playbackJob?.cancel()
        mediaPlayer?.release()
        mediaPlayer = null
        binding.btnPlay.setImageResource(R.drawable.ic_play)
    }

    override fun onStop() { super.onStop(); stopAudio() }
}