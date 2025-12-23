package com.podcastcreateur.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.podcastcreateur.app.databinding.ActivityRecorderBinding
import java.io.File

class RecorderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecorderBinding
    private var isRecording = false
    private var mediaRecorder: MediaRecorder? = null
    private lateinit var outputFile: File
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecorderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val projectPath = intent.getStringExtra("PROJECT_PATH") ?: run { finish(); return }
        val prefix = intent.getStringExtra("CHRONICLE_PREFIX") ?: "999_"
        val name = intent.getStringExtra("CHRONICLE_NAME") ?: "Temp"
        val scriptPath = intent.getStringExtra("SCRIPT_PATH")

        if (scriptPath != null) {
            val scriptFile = File(scriptPath)
            if (scriptFile.exists()) {
                val text = scriptFile.readText()
                if (text.isNotEmpty()) binding.txtScriptDisplay.text = text
            }
        }
        
        outputFile = File(projectPath, "$prefix$name.m4a")

        binding.btnRecordToggle.setOnClickListener {
            if (isRecording) stopRecording() else startRecording()
        }
    }

    private fun startRecording() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, getString(R.string.permission_missing), Toast.LENGTH_SHORT).show()
            return
        }

        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128000)
            setAudioSamplingRate(44100)
            setOutputFile(outputFile.absolutePath)
            try {
                prepare()
                start()
                isRecording = true
                binding.btnRecordToggle.setImageResource(R.drawable.ic_stop)
                binding.chronometer.base = SystemClock.elapsedRealtime()
                binding.chronometer.start()
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (e: Exception) {}
        
        mediaRecorder = null
        isRecording = false
        binding.chronometer.stop()
        binding.btnRecordToggle.setImageResource(R.drawable.ic_record)
        
        Toast.makeText(this, getString(R.string.recording_finished), Toast.LENGTH_SHORT).show()
        
        val intent = Intent(this, EditorActivity::class.java)
        intent.putExtra("FILE_PATH", outputFile.absolutePath)
        startActivity(intent)
        
        finish() 
    }
    
    override fun onStop() {
        super.onStop()
        if (isRecording) stopRecording()
    }
}