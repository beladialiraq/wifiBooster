package com.example.wifibooster

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val perms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val ssid = EditText(this).apply { hint = "WiFi name (SSID)" }
        val pass = EditText(this).apply {
            hint = "Password (needed on Android 10+)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val start = Button(this).apply { text = "Start auto-switching" }
        val stop = Button(this).apply { text = "Stop" }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(ssid); addView(pass); addView(start); addView(stop)
        })

        val needed = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        perms.launch(needed.toTypedArray())

        start.setOnClickListener {
            val i = Intent(this, WifiSwitchService::class.java)
                .putExtra("ssid", ssid.text.toString().trim())
                .putExtra("pass", pass.text.toString())
            ContextCompat.startForegroundService(this, i)
            Toast.makeText(this, "Started", Toast.LENGTH_SHORT).show()
        }
        stop.setOnClickListener { stopService(Intent(this, WifiSwitchService::class.java)) }
    }
}
