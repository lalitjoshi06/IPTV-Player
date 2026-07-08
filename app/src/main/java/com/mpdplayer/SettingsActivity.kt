package com.mpdplayer

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var rvPlaylists: RecyclerView
    private val playlists = mutableListOf<Playlist>()
    private val adapter = PlaylistAdapter()

    private val importFilePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importFromUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE)
        rvPlaylists = findViewById(R.id.rvPlaylistsSettings)
        rvPlaylists.layoutManager = LinearLayoutManager(this)
        rvPlaylists.adapter = adapter

        loadPlaylists()

        findViewById<Button>(R.id.btnAddMore).setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java).putExtra("force_setup", true))
        }

        findViewById<Button>(R.id.btnRefreshAll).setOnClickListener {
            prefs.edit()
                .remove("cached_channels_json")
                .remove("cached_epg_urls")
                .putBoolean("force_refresh", true)
                .apply()
            val cacheDir = cacheDir
            cacheDir.listFiles()?.filter { it.name.startsWith("epg_cache_") }?.forEach { it.delete() }
            Toast.makeText(this, "Refreshing all playlists...", Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<Button>(R.id.btnExportConfig).setOnClickListener { exportBackup() }
        findViewById<Button>(R.id.btnImportConfig).setOnClickListener { importBackup() }
    }

    private fun exportBackup() {
        try {
            val prefs = getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE)
            val json = Gson().toJson(prefs.all)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "tv_player_backup.json")
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: throw Exception("Could not create file in Downloads")
                resolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    ?: throw Exception("Could not write to file")
            } else {
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadDir.exists()) downloadDir.mkdirs()
                File(downloadDir, "tv_player_backup.json").writeText(json)
            }

            Toast.makeText(this, "Backup saved to Downloads/tv_player_backup.json", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importBackup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = contentResolver
            val collectionUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Downloads._ID)
            val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
            val selectionArgs = arrayOf("tv_player_backup.json")

            val cursor = resolver.query(collectionUri, projection, selection, selectionArgs, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                    val uri = android.content.ContentUris.withAppendedId(collectionUri, id)
                    val json = resolver.openInputStream(uri)?.bufferedReader()?.readText()
                    if (json != null) {
                        restoreFromJson(json)
                        return
                    }
                }
            }
        } else {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadDir, "tv_player_backup.json")
            if (file.exists()) {
                restoreFromJson(file.readText())
                return
            }
        }

        showImportPicker()
    }

    private fun showImportPicker() {
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Import Backup")
            .setMessage("No backup file found in Downloads. Select the backup file manually?")
            .setPositiveButton("Pick File") { _, _ ->
                importFilePickerLauncher.launch("application/json")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importFromUri(uri: Uri) {
        try {
            val json = contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
            if (json != null) {
                restoreFromJson(json)
            } else {
                Toast.makeText(this, "Could not read file", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun restoreFromJson(json: String) {
        try {
            val type = object : TypeToken<Map<String, Any?>>() {}.type
            val data: Map<String, Any?> = Gson().fromJson(json, type)

            val prefs = getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE).edit()
            prefs.clear()

            data.forEach { (key, value) ->
                when (value) {
                    is String -> prefs.putString(key, value)
                    is Boolean -> prefs.putBoolean(key, value)
                    is Number -> {
                        val d = value.toDouble()
                        if (d == d.toLong().toDouble()) prefs.putLong(key, d.toLong())
                        else prefs.putFloat(key, d.toFloat())
                    }
                    is List<*> -> {
                        val set = value.filterNotNull().map { it.toString() }.toSet()
                        prefs.putStringSet(key, set)
                    }
                }
            }
            prefs.apply()

            getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE).edit()
                .remove("cached_channels_json").apply()

            Toast.makeText(this, "Backup restored! Restarting...", Toast.LENGTH_LONG).show()
            finishAffinity()
            startActivity(Intent(this, SetupActivity::class.java))
        } catch (e: Exception) {
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadPlaylists() {
        val prefs = getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE)
        val json = prefs.getString("playlists_json", "")
        if (!json.isNullOrEmpty()) {
            val type = object : TypeToken<List<Playlist>>() {}.type
            playlists.clear()
            playlists.addAll(Gson().fromJson(json, type))
            adapter.notifyDataSetChanged()
        }
    }

    private fun savePlaylists() {
        val prefs = getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("playlists_json", Gson().toJson(playlists)).apply()
    }

    private inner class PlaylistAdapter : RecyclerView.Adapter<PlaylistAdapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_playlist_settings, parent, false)
        )
        override fun onBindViewHolder(holder: VH, position: Int) {
            val p = playlists[position]
            holder.cbActive.text = p.name
            holder.tvUrl.text = p.url
            holder.cbActive.isChecked = p.isActive

            holder.itemView.setOnClickListener {
                p.isActive = !p.isActive
                holder.cbActive.isChecked = p.isActive
                savePlaylists()
            }

            holder.itemView.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) view.animate().scaleX(1.02f).scaleY(1.02f).start()
                else view.animate().scaleX(1.0f).scaleY(1.0f).start()
            }

            holder.itemView.setOnLongClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener true
                val playlist = playlists[pos]
                val epgLabel = if (playlist.useEpg) "EPG: ON (tap to disable)" else "EPG: OFF (tap to enable)"
                val options = arrayOf(epgLabel, "Reload This Playlist", "Remove Playlist", "Cancel")
                AlertDialog.Builder(this@SettingsActivity, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
                    .setTitle(playlist.name)
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> {
                                playlist.useEpg = !playlist.useEpg
                                savePlaylists()
                                Toast.makeText(this@SettingsActivity, "EPG ${if (playlist.useEpg) "enabled" else "disabled"} for '${playlist.name}'", Toast.LENGTH_SHORT).show()
                            }
                            1 -> {
                                val prefs = getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE)
                                val reloadSet = prefs.getStringSet("reload_playlists", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                                reloadSet.add(playlist.name)
                                prefs.edit().putStringSet("reload_playlists", reloadSet).apply()
                                Toast.makeText(this@SettingsActivity, "Reloading '${playlist.name}' on return...", Toast.LENGTH_SHORT).show()
                                finish()
                            }
                            2 -> {
                                playlists.removeAt(pos)
                                savePlaylists()
                                notifyDataSetChanged()
                            }
                        }
                    }
                    .show()
                true
            }
        }
        override fun getItemCount() = playlists.size
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val cbActive: CheckBox = v.findViewById(R.id.cbPlaylistActive)
            val tvUrl: TextView = v.findViewById(R.id.tvPlaylistUrl)
        }
    }
}
