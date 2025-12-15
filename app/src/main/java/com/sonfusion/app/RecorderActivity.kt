package com.podcastcreateur.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaRecorder
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.podcastcreateur.app.databinding.ActivityRecorderBinding
import java.io.File

class RecorderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecorderBinding
    private var isRecording = false
    private var mediaRecorder: MediaRecorder? = null
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
        input.hint = "Nom de la chronique"
        input.setTextColor(Color.BLACK)
        input.setHintTextColor(Color.GRAY)
        
        val defaultName = "Ma chronique"
        input.setText(defaultName)
        input.selectAll()

        val container = android.widget.FrameLayout(this)
        val params = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.leftMargin = 50; params.rightMargin = 50
        input.layoutParams = params
        container.addView(input)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Nouvelle chronique")
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("OK", null)
            .setNegativeButton("Annuler") { _, _ -> finish() }
            .create()

        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            var name = input.text.toString().trim()
            if (name.isEmpty()) name = "son_" + System.currentTimeMillis()/1000
            
            // Regex avec accents supportés
            val safeName = name.replace(Regex("[^\\p{L}0-9 _-]"), "")
            // Vérification si le fichier M4A existe
            val potentialFile = File(projectPath, "999_" + safeName + ".m4a")
            
            if (potentialFile.exists()) {
                 Toast.makeText(this, "Ce nom existe déjà", Toast.LENGTH_SHORT).show()
            } else {
                customFileName = safeName
                dialog.dismiss()
            }
        }
    }

    private fun startRecording() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permission manquante", Toast.LENGTH_SHORT).show()
            return
        }

        outputFile = File(projectPath, "999_" + customFileName + ".m4a")

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
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@RecorderActivity, "Erreur initialisation enregistrement", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (e: Exception) { e.printStackTrace() }
        
        mediaRecorder = null
        isRecording = false
        binding.chronometer.stop()
        binding.btnRecordToggle.setImageResource(R.drawable.ic_record)
        
        onRecordingFinished()
    }

    private fun onRecordingFinished() {
        Toast.makeText(this, "Enregistré", Toast.LENGTH_SHORT).show()
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