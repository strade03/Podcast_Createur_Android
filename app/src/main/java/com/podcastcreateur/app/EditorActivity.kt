package com.podcastcreateur.app

import android.content.Intent
import android.media.*
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
    private var playbackJob: Job? = null
    private var currentZoom = 1.0f 

    private var workingPcm: ShortArray? = null
    private var isPcmReady = false
    private var sampleRate = 44100
    private var currentChannels = 1

    private var audioTrack: AudioTrack? = null
    private var backupPlayer: MediaPlayer? = null
    private var isPlaying = false  

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra("FILE_PATH") ?: return finish()
        currentFile = File(path)
        
        binding.txtFilename.text = currentFile.name.replace(Regex("^\\d{3}_"), "")
        binding.waveformView.setZoomLevel(currentZoom)
        binding.waveformView.onPositionChanged = { index -> updateCurrentTimeDisplay(index) }

        binding.btnPlay.setOnClickListener { if(isPlaying) stopAudio() else playAudio() }
        binding.btnCut.setOnClickListener { cutSelection() }
        binding.btnNormalize.setOnClickListener { normalizeSelection() }
        binding.btnSave.setOnClickListener { saveChanges() }
        binding.btnZoomIn.setOnClickListener { applyZoom(currentZoom * 1.5f) }
        binding.btnZoomOut.setOnClickListener { applyZoom(currentZoom / 1.5f) }
        
        updateEditButtons(false)        
        loadEditorData()
    }

    private fun updateEditButtons(enabled: Boolean) {
        isPcmReady = enabled
        val alpha = if (enabled) 1.0f else 0.5f
        binding.btnCut.isEnabled = enabled; binding.btnCut.alpha = alpha
        binding.btnNormalize.isEnabled = enabled; binding.btnNormalize.alpha = alpha
    }

    private fun loadEditorData() {
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch(Dispatchers.IO) {
            metadata = AudioHelper.getAudioMetadata(currentFile)
            val meta = metadata ?: return@launch

            withContext(Dispatchers.Main) {
                binding.txtDuration.text = formatTime(meta.duration)
                binding.waveformView.initialize((meta.duration / 1000) * AudioHelper.POINTS_PER_SECOND)
            }

            // Task 1: Waveform Rapide
            AudioHelper.loadWaveform(currentFile) { newChunk ->
                runOnUiThread {
                    binding.waveformView.appendData(newChunk)
                    binding.progressBar.visibility = View.GONE
                }
            }
        }

        // Task 2: Décodage PCM fond
        lifecycleScope.launch(Dispatchers.IO) {
            val content = AudioHelper.decodeToPCM(currentFile)
            workingPcm = content.data
            sampleRate = content.sampleRate
            currentChannels = 1 
            withContext(Dispatchers.Main) { updateEditButtons(true) }
        }
    }

    private fun playAudio() {
        if (isPcmReady && workingPcm != null) playAudioTrack() else playBackupPlayer()
    }

    private fun playBackupPlayer() {
        stopAudio()
        try {
            backupPlayer = MediaPlayer().apply {
                setDataSource(currentFile.absolutePath)
                prepare()
                val startMs = (binding.waveformView.playheadPos * 1000L) / AudioHelper.POINTS_PER_SECOND
                seekTo(startMs.toInt())
                start()
            }
            isPlaying = true
            binding.btnPlay.setImageResource(R.drawable.ic_stop_read)
            startPlaybackLoop()
        } catch (e: Exception) { Toast.makeText(this, "Chargement...", Toast.LENGTH_SHORT).show() }
    }

    private fun playAudioTrack() {
        val pcm = workingPcm ?: return
        stopAudio()
        
        val samplesPerPoint = sampleRate / AudioHelper.POINTS_PER_SECOND
        var currentSampleOffset = binding.waveformView.playheadPos * samplesPerPoint
        val endLimit = if (binding.waveformView.selectionEnd > binding.waveformView.selectionStart)
                        binding.waveformView.selectionEnd * samplesPerPoint else pcm.size

        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack.Builder()
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(bufferSize).build()

        isPlaying = true
        binding.btnPlay.setImageResource(R.drawable.ic_stop_read)

        playbackJob = lifecycleScope.launch(Dispatchers.IO) {
            audioTrack?.play()
            val temp = ShortArray(bufferSize / 2)
            while (isPlaying && currentSampleOffset < endLimit) {
                val toWrite = minOf(temp.size, endLimit - currentSampleOffset)
                System.arraycopy(pcm, currentSampleOffset, temp, 0, toWrite)
                val written = audioTrack?.write(temp, 0, toWrite) ?: 0
                if (written <= 0) break
                currentSampleOffset += written
                withContext(Dispatchers.Main) {
                    val idx = currentSampleOffset / samplesPerPoint
                    binding.waveformView.playheadPos = idx
                    binding.waveformView.invalidate()
                    updateCurrentTimeDisplay(idx)
                    autoScroll(idx)
                }
            }
            withContext(Dispatchers.Main) { stopAudio() }
        }
    }

    private fun startPlaybackLoop() {
        playbackJob = lifecycleScope.launch {
            while (isPlaying && backupPlayer?.isPlaying == true) {
                val pos = backupPlayer?.currentPosition ?: 0
                val idx = ((pos.toLong() * AudioHelper.POINTS_PER_SECOND) / 1000).toInt()
                binding.waveformView.playheadPos = idx
                binding.waveformView.invalidate()
                updateCurrentTimeDisplay(idx)
                delay(50)
            }
            if (isPlaying) stopAudio()
        }
    }

    private fun stopAudio() {
        isPlaying = false
        playbackJob?.cancel()
        try { audioTrack?.stop(); audioTrack?.release() } catch (e: Exception) {}
        try { backupPlayer?.stop(); backupPlayer?.release() } catch (e: Exception) {}
        audioTrack = null; backupPlayer = null
        binding.btnPlay.setImageResource(R.drawable.ic_play)
    }

    private fun cutSelection() {
        val pcm = workingPcm ?: return
        val startIdx = binding.waveformView.selectionStart
        val endIdx = binding.waveformView.selectionEnd
        if (startIdx < 0 || endIdx <= startIdx) return
        stopAudio()
        val samplesPerPoint = sampleRate / AudioHelper.POINTS_PER_SECOND
        val s = startIdx * samplesPerPoint
        val e = (endIdx * samplesPerPoint).coerceAtMost(pcm.size)
        val newPcm = ShortArray(pcm.size - (e - s))
        System.arraycopy(pcm, 0, newPcm, 0, s)
        System.arraycopy(pcm, e, newPcm, s, pcm.size - e)
        workingPcm = newPcm
        refreshWaveformFromPcm(newPcm)
    }

    private fun normalizeSelection() {
        val pcm = workingPcm ?: return
        stopAudio()
        val samplesPerPoint = sampleRate / AudioHelper.POINTS_PER_SECOND
        val s = (binding.waveformView.selectionStart.coerceAtLeast(0) * samplesPerPoint)
        val e = if(binding.waveformView.selectionEnd > 0) (binding.waveformView.selectionEnd * samplesPerPoint).coerceAtMost(pcm.size) else pcm.size
        lifecycleScope.launch(Dispatchers.Default) {
            var max = 0f
            for (i in s until e) { val v = abs(pcm[i].toFloat() / 32768f); if (v > max) max = v }
            if (max > 0) {
                val gain = 0.95f / max
                for (i in s until e) pcm[i] = (pcm[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
                withContext(Dispatchers.Main) { refreshWaveformFromPcm(pcm) }
            }
        }
    }

    private fun refreshWaveformFromPcm(pcm: ShortArray) {
        lifecycleScope.launch(Dispatchers.Default) {
            val content = AudioContent(pcm, sampleRate, 1)
            val wave = AudioHelper.generateWaveformFromPCM(content)
            withContext(Dispatchers.Main) {
                binding.waveformView.clearData()
                binding.waveformView.appendData(wave)
                binding.txtDuration.text = formatTime((pcm.size * 1000L) / sampleRate)
            }
        }
    }

    private fun saveChanges() {
        val pcm = workingPcm ?: return
        stopAudio()
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val tmp = File(currentFile.parent, "tmp_save.m4a")
            if (AudioHelper.savePCMToAAC(pcm, tmp, sampleRate, 1)) {
                currentFile.delete(); tmp.renameTo(currentFile)
                withContext(Dispatchers.Main) { finish() }
            }
        }
    }

    private fun updateCurrentTimeDisplay(index: Int) {
        binding.txtCurrentTime.text = formatTime(index.toLong() * (1000 / AudioHelper.POINTS_PER_SECOND))
    }
    
    private fun formatTime(ms: Long): String {
        val s = (ms / 1000).toInt()
        return String.format("%02d:%02d", s / 60, s % 60)
    }

    private fun applyZoom(newZoom: Float) {
        currentZoom = newZoom.coerceIn(0.1f, 50.0f)
        binding.waveformView.setZoomLevel(currentZoom)
    }

    private fun autoScroll(idx: Int) {
        val px = binding.waveformView.sampleToPixel(idx)
        val target = (px - binding.scroller.width / 2).toInt().coerceAtLeast(0)
        if (abs(binding.scroller.scrollX - target) > 20) binding.scroller.scrollTo(target, 0)
    }
    
    override fun onStop() { super.onStop(); stopAudio() }
}