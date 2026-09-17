package com.screenmirror.receiver

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.screenmirror.receiver.R
import com.screenmirror.receiver.receiver.ReceiverActivity
import com.screenmirror.receiver.utils.AppPrefs

/**
 * Launcher activity: input 6-digit pairing code, then proceed to ReceiverActivity.
 * Pre-fills with the last-used code (from SharedPreferences).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var codeInput: EditText
    private lateinit var connectButton: Button
    private lateinit var clearCodeButton: Button
    private lateinit var statusText: TextView
    private var prefs: AppPrefs? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = AppPrefs(this)

        codeInput = findViewById(R.id.etCode)
        connectButton = findViewById(R.id.btnConnect)
        clearCodeButton = findViewById(R.id.btnClearCode)
        statusText = findViewById(R.id.tvStatus)

        codeInput.inputType = InputType.TYPE_CLASS_NUMBER

        // Pre-fill saved code if exists
        prefs?.getPairingCode()?.let { saved ->
            codeInput.setText(saved)
            codeInput.setSelection(saved.length)
            statusText.text = "Kode tersimpan dari sesi sebelumnya"
        }

        connectButton.setOnClickListener {
            val code = codeInput.text.toString().trim()
            if (code.length != 6 || !code.all { it.isDigit() }) {
                statusText.text = "Kode harus 6 digit angka"
                return@setOnClickListener
            }
            prefs?.savePairingCode(code)
            val intent = Intent(this, ReceiverActivity::class.java).apply {
                putExtra(ReceiverActivity.EXTRA_PAIRING_CODE, code)
            }
            startActivity(intent)
        }

        clearCodeButton.setOnClickListener {
            prefs?.clearPairingCode()
            codeInput.setText("")
            statusText.text = "Kode tersimpan dihapus"
        }
    }
}
