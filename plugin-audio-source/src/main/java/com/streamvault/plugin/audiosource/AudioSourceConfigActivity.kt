package com.streamvault.plugin.audiosource

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.net.URL

class AudioSourceConfigActivity : Activity() {
    private lateinit var serverInput: EditText
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(8, 10, 15)
        window.navigationBarColor = Color.rgb(8, 10, 15)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.rgb(8, 10, 15))
        }

        fun label(value: String, size: Float = 18f) = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.WHITE)
            setPadding(0, 10, 0, 10)
        }

        root.addView(label("StreamVault Audio Source", 26f))
        root.addView(label("Xtream account used only for external audio channels.", 15f))

        serverInput = field("Server URL", PluginPrefs.server(this))
        usernameInput = field("Username", PluginPrefs.username(this))
        passwordInput = field("Password", PluginPrefs.password(this)).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        root.addView(serverInput)
        root.addView(usernameInput)
        root.addView(passwordInput)

        root.addView(Button(this).apply {
            text = "Save audio account"
            setOnClickListener { saveAccount() }
        }, marginParams())

        root.addView(Button(this).apply {
            text = "Test audio account"
            setOnClickListener { testAccount() }
        }, marginParams())

        val disable = Button(this).apply {
            text = if (PluginPrefs.enabled(this@AudioSourceConfigActivity)) "Disable audio source" else "Enable audio source"
            setOnClickListener {
                val next = !PluginPrefs.enabled(this@AudioSourceConfigActivity)
                PluginPrefs.setEnabled(this@AudioSourceConfigActivity, next)
                text = if (next) "Disable audio source" else "Enable audio source"
                status.text = if (next) "Enabled" else "Disabled"
            }
        }
        root.addView(disable, marginParams())

        status = label(if (PluginPrefs.server(this).isBlank()) "Not configured" else "Configured", 14f)
        root.addView(status)
        setContentView(root)
    }

    private fun field(hint: String, value: String): EditText =
        EditText(this).apply {
            this.hint = hint
            setText(value)
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.rgb(21, 26, 35))
            setPadding(18, 12, 18, 12)
            layoutParams = marginParams()
        }

    private fun saveAccount() {
        val server = serverInput.text.toString().trim()
        val username = usernameInput.text.toString().trim()
        val password = passwordInput.text.toString()
        if (!isHttpUrl(server) || username.isBlank() || password.isBlank()) {
            Toast.makeText(this, "Enter Server URL, Username and Password.", Toast.LENGTH_SHORT).show()
            return
        }
        PluginPrefs.saveAccount(this, server, username, password)
        PluginPrefs.setEnabled(this, true)
        status.text = "Saved"
        Toast.makeText(this, "Audio account saved.", Toast.LENGTH_SHORT).show()
    }

    private fun testAccount() {
        val server = serverInput.text.toString().trim().trimEnd('/')
        val user = usernameInput.text.toString().trim()
        val pass = passwordInput.text.toString()
        if (!isHttpUrl(server) || user.isBlank() || pass.isBlank()) {
            Toast.makeText(this, "Configure the account first.", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            try {
                val url = URL(server + "/player_api.php?username=" +
                    java.net.URLEncoder.encode(user, "UTF-8") +
                    "&password=" + java.net.URLEncoder.encode(pass, "UTF-8") +
                    "&action=get_live_streams")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                val code = connection.responseCode
                connection.disconnect()
                runOnUiThread {
                    Toast.makeText(this, if (code in 200..299) "Xtream account OK." else "Xtream HTTP $code", Toast.LENGTH_LONG).show()
                }
            } catch (error: Exception) {
                runOnUiThread { Toast.makeText(this, error.message ?: "Connection failed", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun isHttpUrl(value: String): Boolean =
        runCatching { val protocol = URL(value).protocol.lowercase(); protocol == "http" || protocol == "https" }.getOrDefault(false)

    private fun marginParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = 10 }
}
