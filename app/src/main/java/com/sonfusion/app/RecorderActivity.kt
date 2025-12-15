package com.podcastcreateur.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton // Import nécessaire
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.podcastcreateur.app.databinding.ActivityRecorderBinding
import java.io.File
import java.io.FileOutputStream

class RecorderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecorderBinding
    private var isRecording = false
    private var recordingThread: Thread? = null
    private lateinit var outputFile: File
    private lateinit var projectPath: String
    private var customFileName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecorderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        projectPath = intent.getStringExtra("PROJECT_PATH") ?: run { finish(); return }
        
        promptForFileName()

        binding.btnRecordToggle.setOnClickListener {
            if (isRecording) stopRecording() else startRecording()
        }
    }
    

    private fun promptForFileName() {
        val input = EditText(this)
        input.hint = "Nom de l'enregistrement"
        input.setTextColor(Color.BLACK)
        input.setHintTextColor(Color.GRAY)
        
        val defaultName = "Ma chronique_" + System.currentTimeMillis()/1000
        input.setText(defaultName)

        val container = android.widget.FrameLayout(this)
        val params = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.leftMargin = 50; params.rightMargin = 50
        input.layoutParams = params
        container.addView(input)

        AlertDialog.Builder(this)
            .setTitle("Nouvel enregistrement")
            .setView(container)
            .setCancelable(false)
            // Astuce pour le focus clavier
            .setOnWindowFocusChangedListener { hasFocus ->
                if (hasFocus) input.requestFocus()
            }
            .setPositiveButton("OK") { _, _ ->
                var name = input.text.toString().trim()
                // Si l'utilisateur vide le champ, on remet une valeur par défaut avec timestamp pour éviter les bugs
                if (name.isEmpty()) name = "son_" + System.currentTimeMillis()/1000
                customFileName = name.replace(Regex("[^a-zA-Z0-9 _-]"), "")
            }
            .setNegativeButton("Annuler") { _, _ ->
                finish()
            }
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permission micro manquante", Toast.LENGTH_SHORT).show()
            return
        }

        outputFile = File(projectPath, "999_" + customFileName + ".wav")

        isRecording = true
        
        // MODIFICATION ICI : Changement d'icone au lieu du texte
        binding.btnRecordToggle.setImageResource(R.drawable.ic_stop)
        
        binding.chronometer.base = SystemClock.elapsedRealtime()
        binding.chronometer.start()

        recordingThread = Thread {
            writeAudioDataToFile()
        }
        recordingThread?.start()
    }

    // ... writeAudioDataToFile() reste inchangé ...
    private fun writeAudioDataToFile() {
        val sampleRate = WavUtils.RECORDER_SAMPLE_RATE
        val channels = AudioFormat.CHANNEL_IN_MONO
        val encoding = WavUtils.RECORDER_AUDIO_ENCODING
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channels, encoding)
        val bufferSize = minBufferSize * 2 
        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channels, encoding, bufferSize)
        val data = ByteArray(bufferSize)

        try {
            recorder.startRecording()
            val os = FileOutputStream(outputFile)
            WavUtils.writeWavHeader(os, 0, 0, sampleRate, 1)
            var totalBytes = 0L

            while (isRecording) {
                val read = recorder.read(data, 0, bufferSize)
                if (read > 0) {
                    os.write(data, 0, read)
                    totalBytes += read
                }
            }
            recorder.stop(); recorder.release(); os.close()
            WavUtils.updateHeader(outputFile, totalBytes, sampleRate, 1)

            runOnUiThread { onRecordingFinished() }

        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun stopRecording() {
        isRecording = false
        binding.chronometer.stop()
        // Remettre l'icone record
        binding.btnRecordToggle.setImageResource(R.drawable.ic_record)
    }

    private fun onRecordingFinished() {
        Toast.makeText(this, "Terminé", Toast.LENGTH_SHORT).show()
        val intent = Intent(this, EditorActivity::class.java)
        intent.putExtra("FILE_PATH", outputFile.absolutePath)
        startActivity(intent)
        finish()
    }
    
    override fun onStop() { 
        super.onStop()
        isRecording = false 
        // Au cas où l'activité est stoppée par le système
        try { binding.chronometer.stop() } catch (e:Exception){}
    }
}