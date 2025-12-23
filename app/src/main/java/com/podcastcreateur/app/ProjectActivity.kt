package com.podcastcreateur.app

import android.content.Intent
import android.media.MediaPlayer
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
import java.util.*
import java.text.Normalizer

data class Chronicle(
    val prefix: String, 
    val name: String,   
    val audioFile: File?, 
    val scriptFile: File 
)

class ProjectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProjectBinding
    private lateinit var projectDir: File
    private val chronicleList = ArrayList<Chronicle>()
    private lateinit var adapter: ChronicleAdapter

    private val importFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) importFileToProject(uri)
    }
    
    // Gestion lecture
    private var mediaPlayer: MediaPlayer? = null
    private var currentPlayingFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProjectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val projectName = intent.getStringExtra("PROJECT_NAME") ?: return finish()
        binding.txtProjectTitle.text = projectName

        val root = File(getExternalFilesDir(null), "Emissions")
        projectDir = File(root, projectName)

        setupRecycler()

        binding.btnRecordNew.text = "Nouvelle Chronique"
        binding.btnRecordNew.setOnClickListener { showNewChronicleDialog() }

        binding.btnImportFile.setOnClickListener { importFileLauncher.launch("audio/*") }
        binding.btnMergeProject.setOnClickListener { performMerge() }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }
    
    override fun onPause() {
        super.onPause()
        stopPreview() // Arrêter la lecture si on quitte l'écran
    }

    private fun setupRecycler() {
        adapter = ChronicleAdapter(chronicleList, this)
        binding.recyclerViewClips.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewClips.adapter = adapter

        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val fromPos = viewHolder.adapterPosition
                val toPos = target.adapterPosition
                Collections.swap(chronicleList, fromPos, toPos)
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
        chronicleList.clear()
        val allFiles = projectDir.listFiles() ?: return
        
        val map = HashMap<String, File>() 
        allFiles.filter { it.name.endsWith(".txt") }.forEach { 
            map[it.name.removeSuffix(".txt")] = it 
        }

        val sortedKeys = map.keys.sorted()
        
        sortedKeys.forEach { key ->
            val txtFile = map[key]!!
            val audioM4a = File(projectDir, "$key.m4a")
            val audioWav = File(projectDir, "$key.wav")
            val audioMp3 = File(projectDir, "$key.mp3")
            
            var audioFile: File? = null
            if (audioM4a.exists()) audioFile = audioM4a
            else if (audioWav.exists()) audioFile = audioWav
            else if (audioMp3.exists()) audioFile = audioMp3

            val match = Regex("^(\\d{3}_)(.*)").find(key)
            if (match != null) {
                val (prefix, name) = match.destructured
                chronicleList.add(Chronicle(prefix, name, audioFile, txtFile))
            }
        }
        
        adapter.notifyDataSetChanged()
    }

    private fun showNewChronicleDialog() {
        val input = EditText(this)
        input.hint = "Titre de la chronique"
        input.setText("Nouvelle chronique")
        input.selectAll()
        input.setTextColor(android.graphics.Color.BLACK)
        val container = FrameLayout(this)
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.leftMargin = 50; params.rightMargin = 50
        input.layoutParams = params
        container.addView(input)

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_new_clip_title))
            .setView(container)
            .setPositiveButton(getString(R.string.btn_new_clip), null)
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .create()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val rawName = input.text.toString().trim()
            if (rawName.isEmpty()) return@setOnClickListener
            val safeName = rawName.replace(Regex("[^\\p{L}0-9 _-]"), "")
            
            if (chronicleList.any { it.name.equals(safeName, ignoreCase = true) }) {
                Toast.makeText(this,  getString(R.string.clip_exists), Toast.LENGTH_SHORT).show()
            } else {
                val prefix = String.format("%03d_", chronicleList.size)
                val txtFile = File(projectDir, "$prefix$safeName.txt")
                if (!txtFile.exists()) txtFile.writeText("")
                refreshList()
                dialog.dismiss()
            }
        }
    }
    
    private fun saveOrderOnDisk() {
        val tempRenames = ArrayList<Pair<File, File>>()
        chronicleList.forEachIndexed { index, item ->
            val newPrefix = String.format("%03d_", index)
            val newKey = newPrefix + item.name
            if (item.prefix != newPrefix) {
                val oldTxt = item.scriptFile
                val newTxt = File(projectDir, "$newKey.txt")
                // ✅ Vérifier collision avant rename
                if (oldTxt.exists() && !newTxt.exists()) {
                    tempRenames.add(oldTxt to newTxt)
                }
                item.audioFile?.let { oldAudio ->
                    val newAudio = File(projectDir, "$newKey.${oldAudio.extension}")
                    if (oldAudio.exists() && !newAudio.exists()) {
                        tempRenames.add(oldAudio to newAudio)
                    }
                }
            }
        }
        // ✅ Renommer en 2 passes : d'abord vers .tmp, puis vers final (optionnel, mais très robuste)
        // Ici version simple mais sûre (on vérifie absence avant)
        
        tempRenames.forEach { (old, new) ->
            if (!new.parentFile.exists()) new.parentFile.mkdirs()
            if (!old.renameTo(new)) {
                // Log ou Toast
                val displayName = new.name.take(50)
                val safeName = displayName.replace(Regex("[\u0000-\u001F\u007F]"), "")

                runOnUiThread {
                    Toast.makeText(this,getString(R.string.error_rename, safeName), Toast.LENGTH_SHORT).show()
                    // Toast.makeText(this,getString(R.string.error_rename, new.toString()), Toast.LENGTH_SHORT).show()
                }
            }
        }
        refreshList()
    }

    private fun importFileToProject(uri: Uri) {
        Thread {
            try {
                var fileName = "import"
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && idx >= 0) fileName = cursor.getString(idx)
                }
                val ext = fileName.substringAfterLast('.', "mp3")
                val cleanName = fileName.substringBeforeLast('.').replace(Regex("[^a-zA-Z0-9 ._-]"), "")
                var finalName = cleanName
                if (chronicleList.any { it.name.equals(finalName, ignoreCase = true) }) finalName += "_" + System.currentTimeMillis()

                val idx = String.format("%03d_", chronicleList.size)
                val destAudio = File(projectDir, "$idx$finalName.$ext")
                val destScript = File(projectDir, "$idx$finalName.txt")
                contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(destAudio).use { output -> input.copyTo(output) } }
                if (!destScript.exists()) destScript.createNewFile() // Script vide

                runOnUiThread { refreshList(); Toast.makeText(this, "Importé", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) { e.printStackTrace() }
        }.start()
    }

    fun onRecordOrPlay(item: Chronicle) {
        if (item.audioFile != null && item.audioFile.exists()) {
            if (currentPlayingFile == item.audioFile) {
                stopPreview() // Clic sur Stop
            } else {
                playPreview(item.audioFile) // Clic sur Play (nouveau fichier)
            }
        } else {
            launchRecorder(item)
        }
    }

    private fun playPreview(file: File) {
        stopPreview() // Arrêter l'ancien
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
                setOnCompletionListener { stopPreview() }
            }
            currentPlayingFile = file
            adapter.notifyDataSetChanged() // Met à  jour les icônes
        } catch (e: Exception) {
            Toast.makeText(this,  getString(R.string.error_read_file), Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopPreview() {
        mediaPlayer?.release()
        mediaPlayer = null
        currentPlayingFile = null
        adapter.notifyDataSetChanged() // Remet les icônes Play
    }
    
    // Helper pour l'adapter
    fun isFilePlaying(file: File?): Boolean {
        return file != null && file == currentPlayingFile
    }

    // --- EXPORT AVEC PROGRESSION ---

       private fun performMerge() {
         val filesToMerge = chronicleList.mapNotNull { it.audioFile }
         if (filesToMerge.isEmpty()) {
             Toast.makeText(this,  getString(R.string.export_no_audio), Toast.LENGTH_SHORT).show()
             return
         }
         
         val progressDialog = AlertDialog.Builder(this)
             .setTitle(getString(R.string.export_in_progress))
             .setMessage(getString(R.string.export_wait))
             .setCancelable(false)
             .create()
         progressDialog.show()

         val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "PodcastCreateur")
         if (!publicDir.exists()) publicDir.mkdirs()
         
         // --- DEBUT MODIFICATION NOMMAGE ---
         
         val originalName = projectDir.name
         
         // 1. Normaliser : Séparer les accents des lettres (ex: "é" devient "e" + "´")
         val normalized = java.text.Normalizer.normalize(originalName, java.text.Normalizer.Form.NFD)
         
         // 2. Supprimer les accents (les marques diacritiques) avec une Regex
         val withoutAccents = normalized.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
         
         // 3. Remplacer les caractères non-alphanumériques (espaces, ponctuation) par des underscores
         // On garde a-z, A-Z, 0-9, . et -
         val safeProjectName = withoutAccents.replace(Regex("[^a-zA-Z0-9.-]"), "_")
         
         // --- FIN MODIFICATION NOMMAGE ---

         val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
         val timestamp = sdf.format(Date())
         
         // Résultat : Nom_Normalise_20231215_1830.m4a
         val outputName = "${safeProjectName}_$timestamp.m4a"
         val destFile = File(publicDir, outputName)
         
         Thread {
             val success = AudioHelper.mergeFiles(filesToMerge, destFile)
             runOnUiThread {
                 progressDialog.dismiss()
                 if (success) {
                     AlertDialog.Builder(this)
                        .setTitle(getString(R.string.export_success_title))
                        // .setMessage(getString(R.string.export_success_msg))
                        .setMessage(getString(R.string.export_success_msg, outputName))
                        .setPositiveButton(getString(R.string.export_success_msg), null)
                        .show()
                 } else {
                     Toast.makeText(this, getString(R.string.export_error), Toast.LENGTH_LONG).show()
                 }
             }
         }.start()
    }

    // --- ACTIONS CLASSIQUES ---
    
    fun onOpenScript(item: Chronicle) {
        val intent = Intent(this, ScriptEditorActivity::class.java)
        intent.putExtra("SCRIPT_PATH", item.scriptFile.absolutePath)
        startActivity(intent)
    }

    private fun launchRecorder(item: Chronicle) {
        val intent = Intent(this, RecorderActivity::class.java)
        intent.putExtra("PROJECT_PATH", projectDir.absolutePath)
        intent.putExtra("CHRONICLE_NAME", item.name)
        intent.putExtra("CHRONICLE_PREFIX", item.prefix)
        intent.putExtra("SCRIPT_PATH", item.scriptFile.absolutePath)
        startActivity(intent)
    }

    fun onEditAudio(item: Chronicle) {
        stopPreview() // Arrêter la lecture avant d'ouvrir l'éditeur
        val intent = Intent(this, EditorActivity::class.java)
        intent.putExtra("FILE_PATH", item.audioFile!!.absolutePath)
        startActivity(intent)
    }

    fun onRename(item: Chronicle) {
        val input = EditText(this)
        input.setText(item.name)
        input.selectAll()
        input.setTextColor(android.graphics.Color.BLACK)
        val container = FrameLayout(this)
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.leftMargin = 50; params.rightMargin = 50
        input.layoutParams = params
        container.addView(input)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.rename_clip))
            .setView(container)
            .setPositiveButton(getString(R.string.btn_rename)) { _, _ ->
                val newName = input.text.toString().trim().replace(Regex("[^\\p{L}0-9 _-]"), "")
                if (newName.isNotEmpty() && newName != item.name) {
                    val oldBase = item.prefix + item.name
                    val newBase = item.prefix + newName
                    item.scriptFile.renameTo(File(projectDir, "$newBase.txt"))
                    item.audioFile?.renameTo(File(projectDir, "$newBase." + item.audioFile.extension))
                    refreshList()
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null).show()
    }
    
    fun onDelete(item: Chronicle) {
        AlertDialog.Builder(this).setTitle(getString(R.string.confirm_delete_clip)).setMessage(item.name).setPositiveButton( getString(R.string.btn_yes)) { _, _ ->
            item.scriptFile.delete()
            item.audioFile?.delete()
            refreshList()
            saveOrderOnDisk() 
        }.setNegativeButton(getString(R.string.btn_no), null).show()
    }
}

class ChronicleAdapter(
    private val list: List<Chronicle>,
    private val activity: ProjectActivity
) : RecyclerView.Adapter<ChronicleAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val txtName: TextView = v.findViewById(R.id.txtClipName)
        val txtStatus: TextView = v.findViewById(R.id.txtStatus)
        val btnScript: ImageButton = v.findViewById(R.id.btnItemScript)
        val btnRecord: ImageButton = v.findViewById(R.id.btnItemRecord)
        val btnMenu: ImageButton = v.findViewById(R.id.btnItemMenu)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_audio_clip, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = list[position]
        holder.txtName.text = item.name
        
        val hasAudio = item.audioFile != null && item.audioFile.exists()
        val scriptLen = if (item.scriptFile.exists()) item.scriptFile.length() else 0
        val scriptStatus = if (scriptLen > 0) "Script OK" else "Script vide"
        val audioStatus = if (hasAudio) "Audio OK" else "Pas d'audio"
        holder.txtStatus.text = "$scriptStatus • $audioStatus"


        // GESTION ICONE LECTURE / STOP / MICRO
        if (hasAudio) {
            // Si c'est le fichier en cours de lecture
            if (activity.isFilePlaying(item.audioFile)) {
                holder.btnRecord.setImageResource(R.drawable.ic_stop_read) // Carré Stop
            } else {
                holder.btnRecord.setImageResource(R.drawable.ic_play) // Triangle Play
            }
        } else {
            holder.btnRecord.setImageResource(R.drawable.ic_record) // Rond Micro
        }

        holder.btnScript.setOnClickListener { activity.onOpenScript(item) }
        holder.btnRecord.setOnClickListener { activity.onRecordOrPlay(item) }
        
        holder.btnMenu.setOnClickListener { 
            val popup = PopupMenu(activity, holder.btnMenu)
            if (hasAudio) popup.menu.add("Éditer l'audio")
            popup.menu.add("Renommer")
            popup.menu.add("Supprimer")
            popup.setOnMenuItemClickListener { menuItem ->
                when(menuItem.title) {
                    "Éditer l'audio" -> activity.onEditAudio(item)
                    "Renommer" -> activity.onRename(item)
                    "Supprimer" -> activity.onDelete(item)
                }
                true
            }
            popup.show()
        }
    }

    override fun getItemCount() = list.size
}
