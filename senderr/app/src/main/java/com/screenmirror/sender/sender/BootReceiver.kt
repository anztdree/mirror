package com.screenmirror.sender.sender

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Auto-start on device boot.
 *
 * NOTE: This requires the user to have launched the app at least once after install
 * (Android restriction: installed apps must be opened at least once before receiving
 * BOOT_COMPLETED broadcasts, since Android 3.1).
 *
 * For a fully automatic boot start without any user interaction,
 * the app needs to be a device-admin or system app, which is out of scope here.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Boot completed — but no auto-start logic yet")
            // For now, do not auto-start (would need stored pairing code to do so)
            // TODO: if user pre-configured pairing code, restart capture service here
        }
    }
}
