package com.screenmirror.sender.utils

import android.content.Context

/**
 * Persisted app preferences using SharedPreferences.
 * Stores the last-generated pairing code so the user does not have to regenerate it.
 */
class AppPrefs(context: Context) {
    companion object {
        private const val PREF_NAME = "screenmirror_sender_prefs"
        private const val KEY_PAIRING_CODE = "pairing_code"
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
