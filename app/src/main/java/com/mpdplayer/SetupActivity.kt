package com.mpdplayer

import android.app.Activity
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
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class SetupActivity : AppCompatActivity() {

    private lateinit var rvPlaylists: RecyclerView
    private val playlists = mutableListOf<Playlist>()
    private val adapter = PlaylistAdapter()

    private val importFilePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importFromUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        loadPlaylistsFromPrefs()

        if (playlists.isNotEmpty() && !intent.getBooleanExtra("force_setup", false)) {
            startMainActivity()
            return
        }

        setContentView(R.layout.activity_setup_multi)

        rvPlaylists = findViewById(R.id.rvPlaylists)
        rvPlaylists.layoutManager = LinearLayoutManager(this)
        rvPlaylists.adapter = adapter

        findViewById<Button>(R.id.btnAddUrl).setOnClickListener { showUrlDialog() }
        findViewById<Button>(R.id.btnAddLocal).setOnClickListener { openFilePicker() }
        findViewById<Button>(R.id.btnRestoreBackup).setOnClickListener { importBackup() }
        findViewById<Button>(R.id.btnContinue).setOnClickListener {
            if (playlists.isEmpty()) {
                Toast.makeText(this, "Add at least one playlist", Toast.LENGTH_SHORT).show()
            } else {
                startMainActivity()
            }
        }
    }

    private fun loadPlaylistsFromPrefs() {
        val prefs = getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE)
        val json = prefs.getString("playlists_json", "")
        if (!json.isNullOrEmpty()) {
            val type = object : TypeToken<List<Playlist>>() {}.type
            playlists.clear()
            playlists.addAll(Gson().fromJson(json, type))
        }
    }

    private fun savePlaylistsToPrefs() {
        val prefs = getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("playlists_json", Gson().toJson(playlists)).apply()
    }

    private fun showUrlDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_playlist, null)
        val etName = view.findViewById<EditText>(R.id.etPlaylistName)
        val etUrl = view.findViewById<EditText>(R.id.etPlaylistUrl)

        AlertDialog.Builder(this)
            .setTitle("Add URL Playlist")
            .setView(view)
            .setPositiveButton("Add") { _, _ ->
                val name = etName.text.toString().trim()
                val url = etUrl.text.toString().trim()
                if (name.isNotEmpty() && url.isNotEmpty()) {
                    playlists.add(Playlist(name, url))
                    savePlaylistsToPrefs()
                    adapter.notifyDataSetChanged()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        filePickerLauncher.launch(intent)
    }

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}

                val builder = AlertDialog.Builder(this)
                builder.setTitle("Playlist Name")
                val input = EditText(this)
                input.hint = "My Local List"
                builder.setView(input)
                builder.setPositiveButton("Add") { _, _ ->
                    val name = input.text.toString().trim().ifBlank { "Local Playlist" }
                    playlists.add(Playlist(name, uri.toString()))
                    savePlaylistsToPrefs()
                    adapter.notifyDataSetChanged()
                }
                builder.show()
            }
        }
    }

    private fun importBackup() {
        var json: String? = null

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
                    json = resolver.openInputStream(uri)?.bufferedReader()?.readText()
                }
            }
        } else {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = java.io.File(downloadDir, "tv_player_backup.json")
            if (file.exists()) {
                json = file.readText()
            }
        }

        if (json != null) {
            restoreFromJson(json)
        } else {
            AlertDialog.Builder(this)
                .setTitle("Import Backup")
                .setMessage("No backup file found in Downloads. Select the backup file manually?")
                .setPositiveButton("Pick File") { _, _ ->
                    importFilePickerLauncher.launch("application/json")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
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

            // Reload playlists from restored prefs
            loadPlaylistsFromPrefs()
            adapter.notifyDataSetChanged()
            findViewById<Button>(R.id.btnContinue).requestFocus()
        } catch (e: Exception) {
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startMainActivity() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private inner class PlaylistAdapter : RecyclerView.Adapter<PlaylistAdapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_playlist, parent, false)
        )
        override fun onBindViewHolder(holder: VH, position: Int) {
            val p = playlists[position]
            holder.text1.text = p.name
            holder.text2.text = p.url

            holder.itemView.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.animate().scaleX(1.02f).scaleY(1.02f).setDuration(200).start()
                } else {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start()
                }
            }

            holder.itemView.setOnLongClickListener {
                playlists.removeAt(holder.bindingAdapterPosition)
                savePlaylistsToPrefs()
                notifyDataSetChanged()
                true
            }
        }
        override fun getItemCount() = playlists.size
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val text1: TextView = v.findViewById(R.id.tvName)
            val text2: TextView = v.findViewById(R.id.tvUrl)
        }
    }
}
