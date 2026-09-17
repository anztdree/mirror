package com.screenmirror.sender.sender

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.screenmirror.sender.R
import com.screenmirror.sender.utils.AppPrefs
import com.screenmirror.sender.utils.CodeGenerator
import com.screenmirror.sender.utils.PermissionHelper

/**
 * Sender activity: shows 6-digit pairing code, requests permissions + MediaProjection,
 * then launches ScreenCaptureService.
 *
 * Saves the last-generated code in SharedPreferences so the user does not have to regenerate
 * on each app launch. The code is reused until the user explicitly regenerates.
 */
class SenderActivity : AppCompatActivity() {

    private lateinit var codeText: TextView
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var regenerateButton: Button
    private var pairingCode: String = ""
    private var prefs: AppPrefs? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sender)

        prefs = AppPrefs(this)

        codeText = findViewById(R.id.tvCode)
        statusText = findViewById(R.id.tvStatus)
        startButton = findViewById(R.id.btnStart)
        stopButton = findViewById(R.id.btnStop)
        regenerateButton = findViewById(R.id.btnRegenerate)

        stopButton.visibility = View.GONE

        // Restore saved code if exists (so user sees same code on next launch)
        prefs?.getPairingCode()?.let { saved ->
            pairingCode = saved
            codeText.text = saved
            statusText.text = "Kode tersimpan. Tekan START untuk mulai."
        }

        startButton.setOnClickListener {
            requestBatteryOptimizationExemptionIfNeeded()
            PermissionHelper.requestPermissions(this)
            if (!PermissionHelper.hasAllPermissions(this)) {
                Toast.makeText(this, "Izin kamera & mikrofon diperlukan", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            requestMediaProjection()
        }

        stopButton.setOnClickListener {
            stopScreenCaptureService()
            statusText.text = "Berhenti. Tekan START untuk mulai lagi."
            startButton.visibility = View.VISIBLE
            stopButton.visibility = View.GONE
        }

        regenerateButton.setOnClickListener {
            pairingCode = CodeGenerator.generate6DigitCode()
            codeText.text = pairingCode
            prefs?.savePairingCode(pairingCode)
            Toast.makeText(this, "Kode baru: $pairingCode", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
    }

    private fun requestMediaProjection() {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), PermissionHelper.REQ_CODE_MEDIA_PROJECTION)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PermissionHelper.REQ_CODE_MEDIA_PROJECTION && resultCode == Activity.RESULT_OK && data != null) {
            // Generate pairing code if not set yet
            if (pairingCode.isEmpty()) {
                pairingCode = CodeGenerator.generate6DigitCode()
            }
            codeText.text = pairingCode
            prefs?.savePairingCode(pairingCode)
            statusText.text = "Menunggu penerima memasang kode..."

            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_PAIRING_CODE, pairingCode)
                putExtra(ScreenCaptureService.EXTRA_MEDIA_PROJECTION_INTENT, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            startButton.visibility = View.GONE
            regenerateButton.visibility = View.GONE
            stopButton.visibility = View.VISIBLE
        } else {
            Toast.makeText(this, "MediaProjection ditolak", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PermissionHelper.REQ_CODE_PERMISSIONS) {
            val denied = grantResults.any { it != android.content.pm.PackageManager.PERMISSION_GRANTED }
            if (denied) {
                Toast.makeText(this, "Beberapa izin ditolak, fitur mungkin tidak berfungsi", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun requestBatteryOptimizationExemptionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
    }

    private fun stopScreenCaptureService() {
        val serviceIntent = Intent(this, ScreenCaptureService::class.java)
        stopService(serviceIntent)
    }
}
