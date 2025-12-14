package com.podcastcreateur.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arthenica.ffmpegkit.FFmpegKit // Import FFmpeg
import com.arthenica.ffmpegkit.ReturnCode
import com.podcastcreateur.app.databinding.ActivityProjectBinding
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

class ProjectActivity : AppCompatActivity() {

    // ... (Le début de la classe reste inchangé jusqu'à importFileToProject) ...
    private lateinit var binding: ActivityProjectBinding
    private lateinit var projectDir: File
    private val audioFiles = ArrayList<File>()
    private lateinit var adapter: AudioClipAdapter

    private val importFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            importFileToProject(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProjectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val projectName = intent.getStringExtra("PROJECT_NAME") ?: return finish()
        binding.txtProjectTitle.text = "Émission : $projectName"

        val root = File(getExternalFilesDir(null), "Emissions")
        projectDir = File(root, projectName)

        setupRecycler()

        binding.btnRecordNew.setOnClickListener {
            val intent = Intent(this, RecorderActivity::class.java)
            intent.putExtra("PROJECT_PATH", projectDir.absolutePath)
            startActivity(intent)
        }

        binding.btnImportFile.setOnClickListener {
            // On autorise audio/* (mp3, m4a, wav, etc.)
            importFileLauncher.launch("audio/*")
        }

        binding.btnMergeProject.setOnClickListener { performMerge() }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }
    
    // ... (setupRecycler, refreshList, saveOrderOnDisk restent inchangés) ...

    private fun setupRecycler() {
        adapter = AudioClipAdapter(audioFiles, this)
        binding.recyclerViewClips.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewClips.adapter = adapter

        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val fromPos = viewHolder.adapterPosition
                val toPos = target.adapterPosition
                Collections.swap(audioFiles, fromPos, toPos)
                adapter.notifyItemMoved(fromPos, toPos)
                return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) { }
            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                saveOrderOnDisk()
            }
        })
        itemTouchHelper.attachToRecyclerView(binding.recyclerViewClips)
    }

    private fun refreshList() {
        audioFiles.clear()
        val list = projectDir.listFiles { f -> 
            f.name.endsWith(".wav") || f.name.endsWith(".mp3") 
        }
        list?.sortedBy { it.name }?.let { audioFiles.addAll(it) }
        adapter.notifyDataSetChanged()
    }

    private fun saveOrderOnDisk() {
        val tempFiles = ArrayList<File>()
        audioFiles.forEachIndexed { index, file ->
            val cleanName = file.name.replace(Regex("^\\d{3}_"), "")
            val newPrefix = String.format("%03d_", index)
            val newFile = File(projectDir, newPrefix + cleanName)
            
            if (file != newFile) {
                file.renameTo(newFile)
                tempFiles.add(newFile)
            } else {
                tempFiles.add(file)
            }
        }
        audioFiles.clear()
        audioFiles.addAll(tempFiles)
        adapter.notifyDataSetChanged()
    }

    // --- MODIFICATION MAJEURE ICI ---

    private fun importFileToProject(uri: Uri) {
        // Afficher un petit loading car la conversion peut prendre 1 ou 2 secondes
        Toast.makeText(this, "Traitement du fichier en cours...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                // 1. Récupérer le nom original
                var fileName = "import_" + System.currentTimeMillis()
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIndex >= 0) {
                        fileName = cursor.getString(nameIndex)
                    }
                }

                // Nettoyage nom et forcer l'extension .wav (car on va convertir)
                val baseName = fileName.substringBeforeLast('.')
                val safeName = baseName.replace(Regex("[^a-zA-Z0-9 ._-]"), "") + ".wav"

                // 2. Créer un fichier temporaire cache pour l'entrée (ex: temp.mp3)
                val tempInputFile = File(cacheDir, "temp_import_source")
                if (tempInputFile.exists()) tempInputFile.delete()
                
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempInputFile).use { output ->
                        input.copyTo(output)
                    }
                }

                // 3. Définir le fichier de destination finale (999_nom.wav)
                val destFile = File(projectDir, "999_" + safeName)

                // 4. Conversion avec FFmpeg
                // -y : écraser si existe
                // -i : entrée
                // -ac 1 : Mono (important pour ton WaveformView)
                // -ar 44100 : 44.1kHz (important pour ton Editor)
                // -c:a pcm_s16le : Encodage WAV standard 16 bits
                val command = "-y -i \"${tempInputFile.absolutePath}\" -ac 1 -ar 44100 -c:a pcm_s16le \"${destFile.absolutePath}\""
                
                val session = FFmpegKit.execute(command)

                if (ReturnCode.isSuccess(session.returnCode)) {
                    // Succès
                    runOnUiThread {
                        refreshList()
                        saveOrderOnDisk()
                        Toast.makeText(this, "Fichier importé et converti !", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // Échec FFmpeg
                    runOnUiThread {
                        Toast.makeText(this, "Erreur conversion format", Toast.LENGTH_LONG).show()
                    }
                }
                
                // Nettoyage cache
                if (tempInputFile.exists()) tempInputFile.delete()

            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Erreur lors de l'import", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    // ... (Le reste : onEdit, onDuplicate, onMerge reste identique) ...

    fun onEdit(file: File) {
        val intent = Intent(this, EditorActivity::class.java)
        intent.putExtra("FILE_PATH", file.absolutePath)
        startActivity(intent)
    }

    fun onDuplicate(file: File) {
        val fullName = file.name.replace(Regex("^\\d{3}_"), "")
        val dotIndex = fullName.lastIndexOf('.')
        val baseName = if (dotIndex != -1) fullName.substring(0, dotIndex) else fullName
        val ext = if (dotIndex != -1) fullName.substring(dotIndex) else ""
        var newBaseName = baseName
        if (!newBaseName.contains("_copie")) {
            newBaseName += "_copie"
        }
        var candidateName = newBaseName + ext
        var counter = 1
        while (isNameTaken(candidateName)) {
            candidateName = "${newBaseName}_$counter$ext"
            counter++
        }
        val dest = File(projectDir, "999_" + candidateName)
        file.copyTo(dest)
        refreshList()
        saveOrderOnDisk()
        Toast.makeText(this, "Dupliqué : $candidateName", Toast.LENGTH_SHORT).show()
    }

    private fun isNameTaken(logicalName: String): Boolean {
        return projectDir.listFiles()?.any { it.name.endsWith("_$logicalName") || it.name == logicalName } == true
    }

    fun onDelete(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Supprimer le clip ?")
            .setMessage(getDisplayName(file))
            .setPositiveButton("Oui") { _, _ ->
                file.delete()
                refreshList()
                saveOrderOnDisk()
            }
            .setNegativeButton("Non", null)
            .show()
    }

    fun getDisplayName(file: File): String {
        return file.name.replace(Regex("^\\d{3}_"), "")
    }

    private fun performMerge() {
        if (audioFiles.isEmpty()) {
            Toast.makeText(this, "Aucun fichier à fusionner", Toast.LENGTH_SHORT).show()
            return
        }
        val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "PodcastCreateur")
        if (!publicDir.exists()) publicDir.mkdirs()

        val safeProjectName = projectDir.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val timestamp = sdf.format(Date())
        val outputName = "${safeProjectName}_$timestamp.wav"
        val destFile = File(publicDir, outputName)

        Toast.makeText(this, "Fusion en cours...", Toast.LENGTH_SHORT).show()
        
        Thread {
            val success = WavUtils.mergeFiles(audioFiles, destFile)
            runOnUiThread {
                if (success) {
                    AlertDialog.Builder(this)
                        .setTitle("Fusion réussie !")
                        .setMessage("Fichier sauvegardé dans :\nMusic/PodcastCreateur/\n$outputName")
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    Toast.makeText(this, "Erreur lors de la fusion", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}