package com.screenmirror.receiver.utils

import android.content.Context

/**
 * Persisted app preferences using SharedPreferences.
 * Stores the last-used pairing code so the user does not have to re-type it
 * every time they open the app.
 */
class AppPrefs(context: Context) {
    companion object {
        private const val PREF_NAME = "screenmirror_prefs"
        private const val KEY_PAIRING_CODE = "pairing_code"
        private const val KEY_LAST_CONNECTED = "last_connected_ts"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun savePairingCode(code: String) {
        prefs.edit().putString(KEY_PAIRING_CODE, code).apply()
    }

    fun getPairingCode(): String? = prefs.getString(KEY_PAIRING_CODE, null)

    fun clearPairingCode() {
        prefs.edit().remove(KEY_PAIRING_CODE).apply()
    }
}
