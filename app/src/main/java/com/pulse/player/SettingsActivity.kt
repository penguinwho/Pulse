package com.pulse.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import com.pulse.player.databinding.ActivitySettingsBinding
import com.pulse.player.databinding.ItemFolderBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(::saveFolder)
    }
    private val cloudflareAuthorization = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, "Cover sharing enabled", Toast.LENGTH_SHORT).show()
        }
        renderCloudflareStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppPreferences.applyTheme(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()
        binding.back.setOnClickListener { finish() }
        binding.addFolder.setOnClickListener { folderPicker.launch(null) }
        binding.openReleases.setOnClickListener { openReleasesPage() }
        binding.enableCloudflareCoverSharing.setOnClickListener {
            cloudflareAuthorization.launch(Intent(this, CloudflareCoverAuthActivity::class.java))
        }
        binding.discordPresence.isChecked = AppPreferences.discordEnabled(this)
        binding.discordPresence.setOnCheckedChangeListener { _, enabled ->
            AppPreferences.setDiscordEnabled(this, enabled)
            if (!enabled) DiscordPresence.clear(this)
        }
        binding.checkForUpdates.setOnClickListener { checkForUpdates() }
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

    private fun openReleasesPage() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/penguinwho/Pulse/releases"))
        if (intent.resolveActivity(packageManager) != null) startActivity(intent)
        else Toast.makeText(this, "No browser is available", Toast.LENGTH_SHORT).show()
    }

    private fun checkForUpdates() {
        binding.checkForUpdates.isEnabled = false
        binding.checkForUpdates.text = "Checking…"
        Thread {
            val result = runCatching {
                val connection = URL("https://api.github.com/repos/penguinwho/Pulse/releases/latest")
                    .openConnection() as HttpURLConnection
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.inputStream.bufferedReader().use { JSONObject(it.readText()).getString("tag_name") }
            }
            runOnUiThread {
                binding.checkForUpdates.isEnabled = true
                binding.checkForUpdates.text = "Check for updates"
                val latest = result.getOrNull()?.removePrefix("v")
                val message = when {
                    latest == null -> "Could not check for updates"
                    latest != BuildConfig.VERSION_NAME -> "Version $latest is available"
                    else -> "Pulse is up to date"
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                if (latest != null && latest != BuildConfig.VERSION_NAME) openReleasesPage()
            }
        }.start()
    }

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.settingsRoot) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.settingsRoot)
    }

    override fun onResume() {
        super.onResume()
        renderCloudflareStatus()
    }

    private fun renderCloudflareStatus() {
        binding.cloudflareStatus.text = if (AppPreferences.cloudflareCoverSession(this).isBlank()) {
            "Cover sharing is off. Enabling it verifies this device before it can upload cover art."
        } else {
            "Cover sharing is enabled. Embedded album art is uploaded only while a song is playing."
        }
    }
}
