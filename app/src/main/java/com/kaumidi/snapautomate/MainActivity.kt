package com.kaumidi.snapautomate

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var autoAcceptSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("snap_automate_prefs", MODE_PRIVATE)

        statusText = findViewById(R.id.statusText)
        autoAcceptSwitch = findViewById(R.id.autoAcceptSwitch)

        autoAcceptSwitch.isChecked =
            prefs.getBoolean(SnapAccessibilityService.PREF_AUTO_ACCEPT, false)

        updateStatus()

        findViewById<Button>(R.id.saveButton).setOnClickListener {
            prefs.edit()
                .putBoolean(
                    SnapAccessibilityService.PREF_AUTO_ACCEPT,
                    autoAcceptSwitch.isChecked
                )
                .apply()

            updateStatus()
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.openAccessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.testButton).setOnClickListener {
            val service = SnapAccessibilityService.instance
            if (service == null) {
                Toast.makeText(
                    this,
                    "Accessibility service ON karo, phir Snapchat ke Accept screen par jao.",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            val clicked = service.acceptVisible()
            Toast.makeText(
                this,
                if (clicked > 0) "Accept clicked" else "Visible Accept button nahi mila",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val serviceOn = SnapAccessibilityService.instance != null
        val enabled = prefs.getBoolean(SnapAccessibilityService.PREF_AUTO_ACCEPT, false)

        statusText.text = when {
            !serviceOn -> "SnapAutomate • Accessibility OFF"
            !enabled -> "SnapAutomate • Auto Accept OFF"
            else -> "SnapAutomate • Auto Accept ON"
        }
    }
}
