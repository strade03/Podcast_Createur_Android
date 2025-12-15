package com.podcastcreateur.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.podcastcreateur.app.databinding.ActivityHomeBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var projectAdapter: ProjectAdapter
    private val projects = ArrayList<File>()
    private lateinit var rootDir: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkPermissions()
        
        rootDir = File(getExternalFilesDir(null), "Emissions")
        if (!rootDir.exists()) rootDir.mkdirs()

        setupRecycler()
        
        binding.btnNewProject.setOnClickListener { showNewProjectDialog() }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun setupRecycler() {
        projectAdapter = ProjectAdapter(projects, 
            onItemClick = { dir ->
                val intent = Intent(this, ProjectActivity::class.java)
                intent.putExtra("PROJECT_NAME", dir.name)
                startActivity(intent)
            },
            onDeleteClick = { dir ->
                showDeleteDialog(dir)
            }
        )
        binding.recyclerViewProjects.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewProjects.adapter = projectAdapter
    }

    private fun refreshList() {
        projects.clear()
        val dirs = rootDir.listFiles { f -> f.isDirectory }
        dirs?.sortedByDescending { it.lastModified() }?.forEach { 
            projects.add(it) 
        }
        projectAdapter.notifyDataSetChanged()
    }

    private fun showNewProjectDialog() {
        val input = EditText(this)
        input.setText("Mon émission")
        input.selectAll()
        input.hint = "Nom de l'émission"
        input.setTextColor(Color.BLACK) 
        input.setHintTextColor(Color.GRAY)
        
        val container = android.widget.FrameLayout(this)
        val params = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.leftMargin = 50; params.rightMargin = 50
        input.layoutParams = params
        container.addView(input)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Nouvelle émission")
            .setView(container)
            .setPositiveButton("Créer", null) 
            .setNegativeButton("Annuler", null)
            .create()

        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = input.text.toString().trim()
            if (name.isNotEmpty()) {
                // Regex autorisant les accents
                val safeName = name.replace(Regex("[^\\p{L}0-9 _-]"), "") 
                val projDir = File(rootDir, safeName)
                if (projDir.exists()) {
                    Toast.makeText(this, "Ce nom existe déjà", Toast.LENGTH_SHORT).show()
                } else {
                    projDir.mkdirs()
                    refreshList()
                    dialog.dismiss() 
                    val intent = Intent(this, ProjectActivity::class.java)
                    intent.putExtra("PROJECT_NAME", safeName)
                    startActivity(intent)
                }
            } else {
                Toast.makeText(this, "Le nom ne peut pas être vide", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showDeleteDialog(dir: File) {
        AlertDialog.Builder(this)
            .setTitle("Supprimer l'émission ?")
            .setMessage("ATTENTION : L'émission '${dir.name}' et tous ses fichiers seront supprimés.")
            .setPositiveButton("Supprimer") { _, _ ->
                dir.deleteRecursively()
                refreshList()
                Toast.makeText(this, "Émission supprimée", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun checkPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT <= 32) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
        }
        
        if (ContextCompat.checkSelfPermission(this, perms[0]) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), 100)
        }
    }

    class ProjectAdapter(
        private val list: List<File>,
        private val onItemClick: (File) -> Unit,
        private val onDeleteClick: (File) -> Unit
    ) : RecyclerView.Adapter<ProjectAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val txtName: TextView = v.findViewById(R.id.txtProjectName)
            val txtDate: TextView = v.findViewById(R.id.txtProjectDate)
            val btnDel: ImageButton = v.findViewById(R.id.btnDeleteProject)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_project, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val dir = list[position]
            holder.txtName.text = dir.name
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            holder.txtDate.text = "Modifié : " + sdf.format(Date(dir.lastModified()))
            holder.itemView.setOnClickListener { onItemClick(dir) }
            holder.btnDel.setOnClickListener { onDeleteClick(dir) }
        }

        override fun getItemCount() = list.size
    }
}