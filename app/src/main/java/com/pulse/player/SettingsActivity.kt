package com.pulse.player

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
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
    private val communityThemePacks = listOf(
        CommunityTheme("Aurora", AppPreferences.OCEAN, "#102337", "#0D6E78"),
        CommunityTheme("Velvet", AppPreferences.AMETHYST, "#20102C", "#6A2A72"),
        CommunityTheme("Ember", AppPreferences.SUNSET, "#351419", "#96382A"),
        CommunityTheme("Forest glass", AppPreferences.OCEAN, "#102A24", "#246548")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        AppPreferences.applyTheme(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyAppearance()
        applySystemBarInsets()
        binding.back.setOnClickListener { closeSettings() }
        binding.addFolder.setOnClickListener { folderPicker.launch(null) }
        binding.connectMusicServer.setOnClickListener { showMusicServerDialog() }
        binding.disconnectMusicServer.setOnClickListener {
            AppPreferences.clearMusicServer(this)
            renderMusicServerStatus()
        }
        binding.openReleases.setOnClickListener { openReleasesPage() }
        binding.enableCloudflareCoverSharing.setOnClickListener {
            cloudflareAuthorization.launch(Intent(this, CloudflareCoverAuthActivity::class.java))
        }
        binding.customizeGradient.setOnClickListener { showCustomGradientDialog() }
        binding.communityThemes.setOnClickListener { showCommunityThemes() }
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
        when (AppPreferences.accent(this)) {
            AppPreferences.OCEAN -> binding.accentOcean.isChecked = true
            AppPreferences.SUNSET -> binding.accentSunset.isChecked = true
            else -> binding.accentAmethyst.isChecked = true
        }
        binding.accentGroup.setOnCheckedChangeListener { _, checkedId ->
            val choice = when (checkedId) {
                R.id.accentOcean -> AppPreferences.OCEAN
                R.id.accentSunset -> AppPreferences.SUNSET
                else -> AppPreferences.AMETHYST
            }
            if (choice != AppPreferences.accent(this)) {
                AppPreferences.setAccent(this, choice)
                applyAppearance()
            }
        }
        renderFolders()
        renderMusicServerStatus()
        renderGradientStatus()
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

    private fun showMusicServerDialog() {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
        }
        val server = EditText(this).apply { hint = "Server URL, for example https://music.example.com"; setText(AppPreferences.musicServerUrl(this@SettingsActivity)) }
        val username = EditText(this).apply { hint = "Username"; setText(AppPreferences.musicServerUsername(this@SettingsActivity)) }
        val password = EditText(this).apply { hint = "Password"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        form.addView(server); form.addView(username); form.addView(password)
        AlertDialog.Builder(this)
            .setTitle("Connect music server")
            .setMessage("Connect a Navidrome, Subsonic, Airsonic, or compatible personal music server.")
            .setView(form)
            .setPositiveButton("Connect", null)
            .setNegativeButton("Cancel", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val url = server.text.toString().trim()
                        val user = username.text.toString().trim()
                        val pass = password.text.toString()
                        if (!(url.startsWith("https://") || url.startsWith("http://")) || user.isBlank() || pass.isBlank()) {
                            Toast.makeText(this, "Enter a server URL, username, and password", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                        Thread {
                            AppPreferences.saveMusicServer(this, url, user, pass)
                            val success = runCatching { SubsonicRepository(applicationContext).verify() }.isSuccess
                            runOnUiThread {
                                if (success) {
                                    dialog.dismiss(); renderMusicServerStatus()
                                    Toast.makeText(this, "Music server connected", Toast.LENGTH_SHORT).show()
                                } else {
                                    AppPreferences.clearMusicServer(this)
                                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                                    Toast.makeText(this, "Could not connect. Check the server details.", Toast.LENGTH_LONG).show()
                                }
                            }
                        }.start()
                    }
                }
                dialog.show()
            }
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

    private fun showCustomGradientDialog() {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val current = AppPreferences.customGradient(this)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
        }
        val start = EditText(this).apply {
            hint = "Start color, for example #20102C"
            setText(current?.first.orEmpty())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        val end = EditText(this).apply {
            hint = "End color, for example #6A2A72"
            setText(current?.second.orEmpty())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        form.addView(start)
        form.addView(end)
        AlertDialog.Builder(this)
            .setTitle("Customize gradient")
            .setMessage("Enter two HEX colors. Your colors are softened automatically in light mode so text remains readable.")
            .setView(form)
            .setPositiveButton("Apply", null)
            .setNeutralButton("Reset", null)
            .setNegativeButton("Cancel", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val first = normalizedHex(start.text.toString())
                        val second = normalizedHex(end.text.toString())
                        if (first == null || second == null) {
                            Toast.makeText(this, "Use six-digit HEX colors, such as #20102C", Toast.LENGTH_LONG).show()
                            return@setOnClickListener
                        }
                        AppPreferences.setCustomGradient(this, first, second)
                        applyAppearance()
                        renderGradientStatus()
                        dialog.dismiss()
                    }
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        AppPreferences.clearCustomGradient(this)
                        applyAppearance()
                        renderGradientStatus()
                        dialog.dismiss()
                    }
                }
                dialog.show()
            }
    }

    private fun showCommunityThemes() {
        AlertDialog.Builder(this)
            .setTitle("Community themes")
            .setMessage("A small offline collection of community-inspired gradients. You can edit any of them afterward.")
            .setItems(communityThemePacks.map(CommunityTheme::name).toTypedArray()) { _, index ->
                val theme = communityThemePacks[index]
                AppPreferences.setAccent(this, theme.accent)
                AppPreferences.setCustomGradient(this, theme.startColor, theme.endColor)
                binding.accentGroup.check(when (theme.accent) {
                    AppPreferences.OCEAN -> R.id.accentOcean
                    AppPreferences.SUNSET -> R.id.accentSunset
                    else -> R.id.accentAmethyst
                })
                applyAppearance()
                renderGradientStatus()
                Toast.makeText(this, "${theme.name} applied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun normalizedHex(value: String): String? {
        val trimmed = value.trim()
        if (!Regex("#[0-9A-Fa-f]{6}").matches(trimmed)) return null
        return runCatching { Color.parseColor(trimmed); trimmed.uppercase() }.getOrNull()
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

    private fun applyAppearance() {
        val palette = PulseAppearance.palette(this)
        PulseAppearance.applyWindow(this, palette)
        PulseAppearance.applyGlass(this, binding.settingsRoot)
        val tint = ColorStateList.valueOf(palette.accent)
        listOf(binding.accentAmethyst, binding.accentOcean, binding.accentSunset).forEach {
            it.buttonTintList = tint
        }
        PulseAppearance.enter(binding.settingsRoot, distanceDp = 10f)
    }

    private fun renderGradientStatus() {
        binding.gradientStatus.text = AppPreferences.customGradient(this)?.let { (start, end) ->
            "Custom gradient: $start to $end"
        } ?: "Using the selected glass color’s default gradient."
    }

    private fun closeSettings() {
        finish()
        overridePendingTransition(R.anim.hold, R.anim.slide_out_right)
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

    private fun renderMusicServerStatus() {
        val connected = AppPreferences.hasMusicServer(this)
        binding.musicServerStatus.text = if (connected) {
            "Connected to ${AppPreferences.musicServerUrl(this)}. Refresh the library to load server music."
        } else "Stream your own library from Navidrome, Subsonic, Airsonic, or another compatible server."
        binding.disconnectMusicServer.visibility = if (connected) View.VISIBLE else View.GONE
    }
}

private data class CommunityTheme(
    val name: String,
    val accent: String,
    val startColor: String,
    val endColor: String,
)
