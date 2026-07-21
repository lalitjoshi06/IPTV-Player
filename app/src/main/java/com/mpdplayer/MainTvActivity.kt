package com.mpdplayer

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.util.UnstableApi

@UnstableApi
class MainTvActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, TvBrowseFragment())
                .commit()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_INFO || keyCode == KeyEvent.KEYCODE_MENU) {
            val fragment = supportFragmentManager.findFragmentById(R.id.fragment_container) as? TvBrowseFragment
            fragment?.toggleFavoriteForSelected()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
