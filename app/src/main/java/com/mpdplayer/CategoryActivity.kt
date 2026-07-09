package com.mpdplayer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.util.UnstableApi
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Collections

@androidx.media3.common.util.UnstableApi
class CategoryActivity : AppCompatActivity() {

    private lateinit var channelList: RecyclerView
    private lateinit var categoryTitle: TextView
    private lateinit var btnBack: ImageButton
    
    private val channels = mutableListOf<Channel>()
    private val adapter = ChannelAdapter()
    private var epgUrls = emptyArray<String>()
    private var categoryName = ""

    private val epgUpdateListener = {
        runOnUiThread { adapter.notifyDataSetChanged() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_category_list)

        channelList = findViewById(R.id.channelList)
        categoryTitle = findViewById(R.id.categoryTitle)
        btnBack = findViewById(R.id.btnBack)

        categoryName = intent.getStringExtra("categoryName") ?: "Channels"
        categoryTitle.text = categoryName
        epgUrls = intent.getStringArrayExtra("epgUrls") ?: emptyArray()

        val json = intent.getStringExtra("channelsJson") ?: ""
        if (json.isNotEmpty()) {
            val type = object : TypeToken<List<Channel>>() {}.type
            channels.addAll(Gson().fromJson(json, type))
        }

        channelList.layoutManager = LinearLayoutManager(this)
        channelList.adapter = adapter
        btnBack.setOnClickListener { finish() }

        EpgManager.addListener(epgUpdateListener)
        
        // Ensure EPG is loaded for passed URLs if not already
        epgUrls.forEach { url ->
            EpgParser.loadEpg(this, url, { data -> EpgManager.updateData(url, data) }, {})
        }

        channelList.post { channelList.getChildAt(0)?.requestFocus() }
    }

    override fun onDestroy() {
        super.onDestroy()
        EpgManager.removeListener(epgUpdateListener)
    }

    private fun getFavorites(): MutableList<String> {
        val prefs = getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE)
        val json = prefs.getString("favorites_list", "[]")
        return Gson().fromJson(json, object : TypeToken<MutableList<String>>() {}.type)
    }

    private fun saveFavorites(list: List<String>) {
        val prefs = getSharedPreferences("mpd_player_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("favorites_list", Gson().toJson(list)).apply()
    }

    private fun toggleFavorite(channel: Channel) {
        val favorites = getFavorites()
        val key = Channel.favoriteKey(channel)
        val oldKey = channel.tvgId.ifBlank { channel.name }
        if (oldKey != key) favorites.remove(oldKey)
        if (favorites.contains(key)) {
            favorites.remove(key)
            if (categoryName == "FAVORITES") {
                val idx = channels.indexOf(channel)
                if (idx >= 0) {
                    channels.removeAt(idx)
                    adapter.notifyItemRemoved(idx)
                }
            }
        } else {
            favorites.add(key)
        }
        saveFavorites(favorites)
        adapter.notifyDataSetChanged()
    }

    private fun moveFavorite(fromIndex: Int, toIndex: Int) {
        if (toIndex < 0 || toIndex >= channels.size) return
        
        val item = channels.removeAt(fromIndex)
        channels.add(toIndex, item)
        
        val newFavOrder = channels.map { Channel.favoriteKey(it) }
        saveFavorites(newFavOrder)
        
        adapter.notifyDataSetChanged()
        
        // Re-open the dialog at the NEW position to allow continuous moving
        channelList.post {
            val viewHolder = channelList.findViewHolderForAdapterPosition(toIndex)
            viewHolder?.itemView?.requestFocus()
            showFavoriteOptions(item, toIndex)
        }
    }

    private fun showFavoriteOptions(channel: Channel, position: Int) {
        val options = arrayOf("Move Up", "Move Down", "Remove from Favorites")
        AlertDialog.Builder(this)
            .setTitle(channel.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> moveFavorite(position, position - 1)
                    1 -> moveFavorite(position, position + 1)
                    2 -> toggleFavorite(channel)
                }
            }
            .show()
    }

    private inner class ChannelAdapter : RecyclerView.Adapter<ChannelAdapter.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        )
        @UnstableApi
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val ch = channels[position]
            val isFav = getFavorites().contains(Channel.favoriteKey(ch))
            holder.name.text = ch.name
            holder.group.text = ch.group
            holder.number.text = ch.tvgId
            holder.btnFav.setImageResource(if (isFav) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off)
            
            // Show current program info
            val epg = EpgManager.getEpgForChannel(ch.tvgId, ch.name)
            val current = epg?.getCurrentProgram()
            if (current != null) {
                holder.epgInfo.text = "LIVE: ${current.title}"
                holder.epgInfo.visibility = View.VISIBLE
            } else {
                holder.epgInfo.visibility = View.GONE
            }

            // Focus animation
            holder.itemView.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.animate().scaleX(1.03f).scaleY(1.03f).setDuration(200).start()
                } else {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start()
                }
            }

            // Show source name(s)
            if (ch.sources.isNotEmpty()) {
                val names = ch.sources.map { it.playlistName }.distinct().joinToString(" | ")
                holder.sourceName.text = names
                holder.sourceName.visibility = View.VISIBLE
            } else {
                holder.sourceName.visibility = View.GONE
            }

            holder.itemView.setOnClickListener {
                val intent = Intent(this@CategoryActivity, PlayerActivity::class.java)
                intent.putExtra("channelName", ch.name)
                intent.putExtra("mpdUrl", ch.mpdUrl)
                intent.putExtra("licenseUrl", ch.licenseUrl)
                intent.putExtra("drmType", ch.drmType)
                intent.putExtra("channelTvgId", ch.tvgId)
                intent.putExtra("epgUrls", epgUrls)
                intent.putExtra("currentIndex", position)
                intent.putExtra("channelsJson", Gson().toJson(channels))
                intent.putExtra("categoryName", categoryName)
                startActivity(intent)
            }

            holder.itemView.setOnLongClickListener {
                if (categoryName == "FAVORITES") {
                    showFavoriteOptions(ch, position)
                } else {
                    toggleFavorite(ch)
                    Toast.makeText(this@CategoryActivity, "Updated Favorites", Toast.LENGTH_SHORT).show()
                }
                true
            }
        }

        override fun getItemCount() = channels.size
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.channelName)
            val group: TextView = v.findViewById(R.id.channelGroup)
            val number: TextView = v.findViewById(R.id.channelNumber)
            val btnFav: ImageView = v.findViewById(R.id.btnFavorite)
            val sourceName: TextView = v.findViewById(R.id.channelSourceName)
            val epgInfo: TextView = v.findViewById(R.id.currentProgramInfo)
        }
    }
}
