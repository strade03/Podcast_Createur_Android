package com.podcastcreateur.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.podcastcreateur.app.databinding.ActivityProjectBinding
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

class ProjectActivity : AppCompatActivity() {

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
            importFileLauncher.launch("audio/*")
        }

        binding.btnMergeProject.setOnClickListener { performMerge() }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

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
        // Supporte m4a, mp3, wav
        val list = projectDir.listFiles { f -> 
            f.name.endsWith(".m4a") || f.name.endsWith(".mp3") || f.name.endsWith(".wav") 
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

    private fun importFileToProject(uri: Uri) {
        Thread {
            try {
                var fileName = "import_" + System.currentTimeMillis()
                var extension = ""
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIndex >= 0) {
                        fileName = cursor.getString(nameIndex)
                    }
                }

                if (fileName.lowercase().endsWith(".mp3")) extension = ".mp3"
                else if (fileName.lowercase().endsWith(".wav")) extension = ".wav"
                else if (fileName.lowercase().endsWith(".m4a")) extension = ".m4a"
                
                if (extension.isEmpty()) {
                     runOnUiThread { Toast.makeText(this, "Format non supporté (MP3, WAV, M4A)", Toast.LENGTH_SHORT).show() }
                     return@Thread
                }

                val baseName = fileName.substringBeforeLast('.')
                val safeName = baseName.replace(Regex("[^a-zA-Z0-9 ._-]"), "") + extension
                val destFile = File(projectDir, "999_" + safeName)

                // Copie directe
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }

                runOnUiThread {
                    refreshList()
                    saveOrderOnDisk()
                    Toast.makeText(this, "Importé !", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { Toast.makeText(this, "Erreur d'import", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

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
        if (!newBaseName.contains("_copie")) newBaseName += "_copie"

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
        Toast.makeText(this, "Dupliqué", Toast.LENGTH_SHORT).show()
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
        if (audioFiles.isEmpty()) return
        
        val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "PodcastCreateur")
        if (!publicDir.exists()) publicDir.mkdirs()

        val safeProjectName = projectDir.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val timestamp = sdf.format(Date())

        // Export final en M4A
        val outputName = "${safeProjectName}_$timestamp.m4a"
        val destFile = File(publicDir, outputName)

        Toast.makeText(this, "Fusion en cours...", Toast.LENGTH_SHORT).show()
        
        Thread {
            val success = AudioHelper.mergeFiles(audioFiles, destFile)
            runOnUiThread {
                if (success) {
                    AlertDialog.Builder(this)
                        .setTitle("Fusion réussie !")
                        .setMessage("Sauvegardé : Music/PodcastCreateur/\n$outputName")
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    Toast.makeText(this, "Erreur lors de la fusion", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}

class AudioClipAdapter(
    private val list: List<File>,
    private val activity: ProjectActivity
) : RecyclerView.Adapter<AudioClipAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val txtName: TextView = v.findViewById(R.id.txtClipName)
        val btnEdit: ImageButton = v.findViewById(R.id.btnItemEdit)
        val btnDup: ImageButton = v.findViewById(R.id.btnItemDuplicate)
        val btnDel: ImageButton = v.findViewById(R.id.btnItemDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_audio_clip, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val file = list[position]
        holder.txtName.text = activity.getDisplayName(file)
        holder.btnEdit.setOnClickListener { activity.onEdit(file) }
        holder.btnDup.setOnClickListener { activity.onDuplicate(file) }
        holder.btnDel.setOnClickListener { activity.onDelete(file) }
    }

    override fun getItemCount() = list.size
}