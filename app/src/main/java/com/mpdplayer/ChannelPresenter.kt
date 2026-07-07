package com.mpdplayer

import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide

class ChannelPresenter(
    private val epgData: Map<String, EpgData>
) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
        val cardView = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(
                ContextCompat.getColor(parent.context, R.color.surface_dark)
            )
            setMainImageDimensions(200, 112)
            cardType = ImageCardView.CARD_TYPE_INFO_UNDER
            infoVisibility = ImageCardView.CARD_REGION_VISIBLE_ALWAYS
        }
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
        val channel = item as? Channel ?: return
        val cardView = viewHolder.view as ImageCardView

        cardView.titleText = channel.name
        
        val sourceInfo = if (channel.sources.isNotEmpty()) {
            channel.sources.map { it.playlistName }.distinct().joinToString(", ")
        } else {
            channel.group
        }
        cardView.contentText = sourceInfo

        if (channel.logoUrl.isNotBlank()) {
            Glide.with(cardView.context)
                .load(channel.logoUrl)
                .override(200, 112)
                .centerCrop()
                .into(cardView.mainImageView!!)
        } else {
            cardView.mainImageView?.setImageResource(android.R.color.transparent)
        }
    }

    override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        cardView.mainImageView?.setImageDrawable(null)
    }

    class ViewHolder(view: ImageCardView) : Presenter.ViewHolder(view)
}
