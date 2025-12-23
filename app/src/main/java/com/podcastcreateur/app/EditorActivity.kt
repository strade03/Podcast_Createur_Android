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
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    // ✅ Cache du peak calculé depuis les amplitudes (pour éviter calculatePeak)
    private var cachedPeakFromWaveform: Float = 0f

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
        binding.btnPlay.setOnClickListener { if (mediaPlayer?.isPlaying == true) stopAudio() else playAudio() }
        binding.btnCut.setOnClickListener { performVirtualCut() }
        binding.btnNormalize.setOnClickListener { prepareVirtualNormalization() }
        binding.btnSave.setOnClickListener { saveChangesAndExit() }
        binding.btnZoomIn.setOnClickListener { applyZoom(currentZoom * 1.5f) }
        binding.btnZoomOut.setOnClickListener { applyZoom(currentZoom / 1.5f) }
        binding.btnReRecord.setOnClickListener {
            AlertDialog.Builder(this).setTitle(R.string.confirm_delete_clip)
                .setMessage(R.string.confirm_delete_clip)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    stopAudio()
                    val regex = Regex("^(\\d{3}_)(.*)\\.(.*)$")
                    val match = regex.find(currentFile.name)
                    if (match != null) {
                        val (_, name, ext) = match.destructured
                        val intent = Intent(this, RecorderActivity::class.java)
                        intent.putExtra("PROJECT_PATH", currentFile.parent)
                        intent.putExtra("CHRONICLE_NAME", name)
                        intent.putExtra("CHRONICLE_PREFIX", match.groupValues[1])
                        startActivity(intent)
                        finish()
                    } else {
                        Toast.makeText(this, getString(R.string.error_file_read), Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
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
            val durationSec = (totalDurationMs / 1000).coerceAtLeast(1)
            val requestPps = when {
                durationSec < 60 -> 1000
                durationSec < 300 -> 400
                durationSec < 900 -> 100
                else -> 50
            }

            amplituda.processAudio(
                currentFile.absolutePath,
                Compress.withParams(Compress.AVERAGE, requestPps)
            ).get({ result ->
                val amplitudes = result.amplitudesAsList()
                if (amplitudes.isNotEmpty() && totalDurationMs > 0) {
                    msPerPoint = totalDurationMs.toDouble() / amplitudes.size.toDouble()
                    // ✅ Précalculer le peak depuis les amplitudes (normalisé 0–1 → 0–32768)
                    // Amplituda renvoie des shorts non signés (~0 à 32767), donc max ≈ 32767
                    cachedPeakFromWaveform = amplitudes.maxOrNull()!!.toFloat() / 32768f
                }
                val maxVal = amplitudes.maxOrNull() ?: 1
                val floats = FloatArray(amplitudes.size) { i ->
                    amplitudes[i].toFloat() / maxVal.toFloat()
                }
                runOnUiThread {
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
                error.printStackTrace()
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    if (binding.waveformView.getPointsCount() == 0) {
                        Toast.makeText(this@EditorActivity, getString(R.string.error_file_read), Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }
    }

    private fun prepareVirtualNormalization() {
        stopAudio()
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            // ✅ Utiliser d'abord le peak approximatif depuis waveform
            var peak = cachedPeakFromWaveform
            // ✅ Seulement si trop bas (< -30 dB) → relancer calculatePeak (cas rare)
            if (peak < 0.03f) {
                // Recalcul précis si waveform peu fiable (ex: silence en début/fichier court)
                peak = AudioHelper.calculatePeak(currentFile)
            }
            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.GONE
                if (peak > 0.01f) {
                    pendingGain = 0.98f / peak  // 2% de headroom
                    Toast.makeText(this@EditorActivity, getString(R.string.normalization_ready), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@EditorActivity, getString(R.string.audio_too_quiet), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveChangesAndExit() {
        if (pendingCuts.isEmpty() && pendingGain == 1.0f) {
            finish()
            return
        }
        stopAudio()
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            // ✅ Nom temporaire sécurisé avec timestamp (éviter conflit)
            val tmpName = "tmp_${System.currentTimeMillis()}_final.m4a"
            val tmpFile = File(currentFile.parent, tmpName)
            if (tmpFile.exists()) {
                tmpFile.delete() // nettoyage préventif
            }

            val meta = AudioHelper.getAudioMetadata(currentFile)
            if (meta == null) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@EditorActivity, getString(R.string.error_file_read), Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            // Convertir coupes MS → échantillons (mono ou stéréo)
            val samplesPerMs = (meta.sampleRate * meta.channelCount) / 1000.0
            val samplesCuts = pendingCuts.map { cut ->
                val sStart = (cut.first * samplesPerMs).toLong()
                val sEnd = (cut.second * samplesPerMs).toLong()
                Pair(sStart, sEnd)
            }

            val success = AudioHelper.saveWithCutsAndGain(currentFile, tmpFile, samplesCuts, pendingGain)
            withContext(Dispatchers.Main) {
                if (success) {
                    // ✅ Suppression + renommage atomique
                    currentFile.delete()
                    if (tmpFile.renameTo(currentFile)) {
                        Toast.makeText(this@EditorActivity, getString(R.string.saved_success), Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        // Echec de rename (très rare)
                        tmpFile.delete()
                        Toast.makeText(this@EditorActivity, getString(R.string.error_save), Toast.LENGTH_SHORT).show()
                        binding.progressBar.visibility = View.GONE
                    }
                } else {
                    tmpFile.delete()
                    Toast.makeText(this@EditorActivity, getString(R.string.error_save), Toast.LENGTH_SHORT).show()
                    binding.progressBar.visibility = View.GONE
                }
            }
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
        binding.txtDuration.text = formatTime(totalDurationMs - totalCutMs)
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
        currentZoom = newZoom.coerceIn(0.1f, 50.0f)
        binding.waveformView.setZoomLevel(currentZoom)
    }

    private fun playAudio() {
        if (mediaPlayer == null) {
            try {
                mediaPlayer = MediaPlayer()
                mediaPlayer!!.setDataSource(currentFile.absolutePath)
                // Calculer offset en échantillons
                // val visualIdx = binding.waveformView.getPlayheadIndex()
                val visualIdx = binding.waveformView.playheadPos
                val visualMs = (visualIdx * msPerPoint).toLong()
                val realMs = mapVisualToRealTime(visualMs)
                mediaPlayer!!.setOnPreparedListener {
                    it.seekTo(realMs.toInt())
                    it.start()
                    binding.btnPlay.setImageResource(android.R.drawable.ic_media_pause)
                    startAutoScroll()
                }
                mediaPlayer!!.prepareAsync()
                binding.progressBar.visibility = View.VISIBLE
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, getString(R.string.error_audio_play), Toast.LENGTH_SHORT).show()
                binding.progressBar.visibility = View.GONE
            }
        } else {
            if (mediaPlayer!!.isPlaying) {
                mediaPlayer!!.pause()
                stopAutoScroll()
                binding.btnPlay.setImageResource(android.R.drawable.ic_media_play)
            } else {
                mediaPlayer!!.start()
                binding.btnPlay.setImageResource(android.R.drawable.ic_media_pause)
                startAutoScroll()
            }
        }
    }

    private fun startAutoScroll() {
        stopAutoScroll()
        playbackJob = lifecycleScope.launch {
            while (isActive && mediaPlayer?.isPlaying == true) {
                val posMs = mediaPlayer!!.currentPosition.toLong()
                val visualIdx = (posMs / msPerPoint).toInt()
                withContext(Dispatchers.Main) {
                    binding.waveformView.setPlayhead(visualIdx)
                    autoScroll(visualIdx)
                }
                delay(50)
            }
            withContext(Dispatchers.Main) {
                binding.btnPlay.setImageResource(android.R.drawable.ic_media_play)
            }
        }
    }

    private fun autoScroll(sampleIdx: Int) {
        val pointWidth = binding.waveformView.pointWidth
        val playheadPixel = (sampleIdx.toFloat() * pointWidth).toInt()
        val scrollerWidth = binding.scroller.width
        val margin = scrollerWidth / 4
        val currentScroll = binding.scroller.scrollX
        val target = playheadPixel - margin
        if (playheadPixel < currentScroll || playheadPixel > currentScroll + scrollerWidth - margin * 2) {
            binding.scroller.smoothScrollTo(target, 0)
        }
    }

    private fun stopAutoScroll() {
        playbackJob?.cancel()
        playbackJob = null
    }

    private fun stopAudio() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (ignored: Exception) {}
        mediaPlayer = null
        stopAutoScroll()
        binding.btnPlay.setImageResource(android.R.drawable.ic_media_play)
    }

    override fun onStop() {
        super.onStop()
        stopAudio()
    }

    override fun onDestroy() {
        super.onDestroy()
        // ✅ Libération explicite des ressources lourdes
        try {
            amplituda.cancel()
        } catch (ignored: Exception) {}
        // Optionnel : binding.waveformView.clearPoints() si tu implémentes
    }
}