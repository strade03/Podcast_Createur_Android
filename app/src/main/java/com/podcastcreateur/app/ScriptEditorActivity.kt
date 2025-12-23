package com.podcastcreateur.app

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.nio.charset.Charset

class ScriptEditorActivity : AppCompatActivity() {

    private lateinit var scriptFile: File
    private lateinit var inputScript: EditText

    // 4. Gestionnaire d'import de texte
    private val importTextLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    // Lire le contenu (en UTF-8 par défaut)
                    val text = inputStream.readBytes().toString(Charset.defaultCharset())
                    // On remplace le texte existant (ou inputScript.append(text) si tu préféres)
                    inputScript.setText(text)
                    Toast.makeText(this,  getString(R.string.text_imported), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this,  getString(R.string.error_read_file), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_script_editor)

        val path = intent.getStringExtra("SCRIPT_PATH")
        if (path == null) { finish(); return }
        scriptFile = File(path)

        inputScript = findViewById(R.id.inputScript)
        val btnSave = findViewById<Button>(R.id.btnSaveScript)
        val btnImport = findViewById<Button>(R.id.btnImportText)

        // Charger le contenu existant
        if (scriptFile.exists()) {
            inputScript.setText(scriptFile.readText())
        }

        // Action Importer
        btnImport.setOnClickListener {
            importTextLauncher.launch("text/plain") // Filtre fichiers texte
        }

        // Action Sauvegarder
        btnSave.setOnClickListener {
            scriptFile.writeText(inputScript.text.toString())
            Toast.makeText(this, getString(R.string.script_saved), Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
