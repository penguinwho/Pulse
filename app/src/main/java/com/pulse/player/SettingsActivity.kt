package com.pulse.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.pulse.player.databinding.ActivitySettingsBinding
import com.pulse.player.databinding.ItemFolderBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(::saveFolder)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppPreferences.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.back.setOnClickListener { finish() }
        binding.addFolder.setOnClickListener { folderPicker.launch(null) }
        binding.version.text = "Pulse ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        when (AppPreferences.theme(this)) {
            AppPreferences.LIGHT -> binding.themeLight.isChecked = true
            AppPreferences.DARK -> binding.themeDark.isChecked = true
            else -> binding.themeSystem.isChecked = true
        }
        binding.themeGroup.setOnCheckedChangeListener { _, checkedId ->
            val choice = when (checkedId) {
                R.id.themeLight -> AppPreferences.LIGHT
                R.id.themeDark -> AppPreferences.DARK
                else -> AppPreferences.SYSTEM
            }
            if (choice != AppPreferences.theme(this)) AppPreferences.setTheme(this, choice)
        }
        renderFolders()
    }

    private fun saveFolder(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            AppPreferences.addFolder(this, uri)
            renderFolders()
        } catch (_: SecurityException) {
            // The document provider did not grant durable access; do not list an unusable folder.
        }
    }

    private fun renderFolders() {
        binding.folders.removeAllViews()
        val uris = AppPreferences.folderUris(this)
        if (uris.isEmpty()) {
            binding.folders.addView(TextView(this).apply {
                text = "No extra folders added"
                setTextColor(getColor(R.color.pulse_muted))
                textSize = 14f
                setPadding(0, 8, 0, 8)
            })
            return
        }
        uris.forEach { uri ->
            val row = ItemFolderBinding.inflate(LayoutInflater.from(this), binding.folders, false)
            row.folderName.text = DocumentFile.fromTreeUri(this, uri)?.name ?: uri.lastPathSegment ?: "Music folder"
            row.removeFolder.setOnClickListener {
                contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                AppPreferences.removeFolder(this, uri)
                renderFolders()
            }
            binding.folders.addView(row.root)
        }
    }
}
