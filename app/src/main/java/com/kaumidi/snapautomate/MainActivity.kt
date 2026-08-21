package com.kaumidi.snapautomate

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("snap_automate_prefs", MODE_PRIVATE)

        val statusText = findViewById<TextView>(R.id.statusText)
        val autoAcceptSwitch = findViewById<Switch>(R.id.autoAcceptSwitch)
        val autoMessageSwitch = findViewById<Switch>(R.id.autoMessageSwitch)
        val messageInput = findViewById<EditText>(R.id.messageInput)
        val saveButton = findViewById<Button>(R.id.saveButton)
        val openAccessibilityButton = findViewById<Button>(R.id.openAccessibilityButton)

        autoAcceptSwitch.isChecked = prefs.getBoolean(SnapAccessibilityService.PREF_AUTO_ACCEPT, false)
        autoMessageSwitch.isChecked = prefs.getBoolean(SnapAccessibilityService.PREF_AUTO_MESSAGE, false)
        messageInput.setText(prefs.getString(SnapAccessibilityService.PREF_MESSAGE_TEXT, "Hey {naam}! Thanks for adding me 🙌"))

        saveButton.setOnClickListener {
            prefs.edit()
                .putBoolean(SnapAccessibilityService.PREF_AUTO_ACCEPT, autoAcceptSwitch.isChecked)
                .putBoolean(SnapAccessibilityService.PREF_AUTO_MESSAGE, autoMessageSwitch.isChecked)
                .putString(SnapAccessibilityService.PREF_MESSAGE_TEXT, messageInput.text.toString())
                .apply()
            statusText.text = "Saved. Turn on Accessibility permission below if not done yet."
        }

        openAccessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        val acceptAllButton = findViewById<Button>(R.id.acceptAllButton)
        val messageAllButton = findViewById<Button>(R.id.messageAllButton)

        acceptAllButton.setOnClickListener {
            val service = SnapAccessibilityService.instance
            if (service == null) {
                Toast.makeText(this, "Pehle Accessibility permission ON karo", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            Toast.makeText(this, "Snapchat khol ke Friend Requests screen par jaake wapas aao", Toast.LENGTH_LONG).show()
            runAcceptAllLoop(service, passes = 8)
        }

        messageAllButton.setOnClickListener {
            val service = SnapAccessibilityService.instance
            if (service == null) {
                Toast.makeText(this, "Pehle Accessibility permission ON karo", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val msg = messageInput.text.toString()
            if (msg.isBlank()) {
                Toast.makeText(this, "Pehle message text likho aur Save karo", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            Toast.makeText(this, "Snapchat me Chat list screen khol ke wapas aao", Toast.LENGTH_LONG).show()
            runMessageAllLoop(service, msg, remaining = 15)
        }
    }

    /**
     * Repeatedly scans + clicks Accept, scrolling between passes, so multiple
     * requests down the list get accepted with a single tap on our button.
     * Runs on the main thread with delays because the accessibility service
     * needs time for Snapchat's UI to update between each click.
     */
    private fun runAcceptAllLoop(service: SnapAccessibilityService, passes: Int) {
        if (passes <= 0) return
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({
            val root = service.getActiveRoot()
            if (root != null) {
                val clicked = service.acceptAllVisible(root)
                root.recycle()
                if (clicked == 0) {
                    service.scrollDown()
                }
            }
            runAcceptAllLoop(service, passes - 1)
        }, 700)
    }

    /**
     * Taps each "New Friend" chat row one at a time, waits for the chat
     * screen to open, types + sends the message, then goes back and
     * moves to the next row. Best-effort: relies on text-label matching,
     * so if Snapchat's wording differs it may miss some rows.
     */
    private fun runMessageAllLoop(service: SnapAccessibilityService, msg: String, remaining: Int) {
        if (remaining <= 0) return
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({
            val opened = service.messageAllVisible(msg)
            if (opened > 0) {
                handler.postDelayed({
                    val finalMsg = service.buildMessageForOpenedChat(msg)
                    service.typeAndSend(finalMsg)
                    handler.postDelayed({
                        service.goBack()
                        runMessageAllLoop(service, msg, remaining - 1)
                    }, 800)
                }, 900)
            }
            // if nothing opened, stop — no more new-friend rows visible
        }, 700)
    }
}
