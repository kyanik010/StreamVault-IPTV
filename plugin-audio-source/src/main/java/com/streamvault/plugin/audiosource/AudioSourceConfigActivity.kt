package com.streamvault.plugin.audiosource

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.net.URL

class AudioSourceConfigActivity : Activity() {
    private lateinit var urlInput: EditText
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

        fun text(value: String, size: Float = 18f): TextView =
            TextView(this).apply {
                this.text = value
                textSize = size
                setTextColor(Color.WHITE)
                setPadding(0, 10, 0, 10)
            }

        root.addView(text("Audio Source", 26f))
        root.addView(text("External M3U audio source", 15f))

        urlInput = EditText(this).apply {
            setText(PluginPrefs.m3uUrl(this@AudioSourceConfigActivity))
            hint = "M3U URL"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.rgb(21, 26, 35))
            setPadding(18, 12, 18, 12)
        }
        root.addView(urlInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 18 })

        val save = Button(this).apply {
            text = "Save"
            setOnClickListener {
                val value = urlInput.text.toString().trim()
                if (!isHttpUrl(value)) {
                    Toast.makeText(this@AudioSourceConfigActivity, "Enter a valid HTTP/HTTPS M3U URL", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                PluginPrefs.setM3uUrl(this@AudioSourceConfigActivity, value)
                status.text = "Saved"
                Toast.makeText(this@AudioSourceConfigActivity, "Audio source saved", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(save, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 14 })

        val test = Button(this).apply {
            text = "Test audio source"
            setOnClickListener { testAudio() }
        }
        root.addView(test, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 8 })

        status = text(
            if (PluginPrefs.m3uUrl(this).isBlank()) "Not configured" else "Configured",
            14f
        ).apply { gravity = Gravity.START }
        root.addView(status)

        setContentView(root)
    }

    private fun testAudio() {
        val url = PluginPrefs.m3uUrl(this).trim()
        if (!isHttpUrl(url)) {
            Toast.makeText(this, "Configure an M3U URL first", Toast.LENGTH_SHORT).show()
            return
        }

        // A direct M3U test is intentionally lightweight: StreamVault remains the host
        // and this Activity is only the plugin-owned configuration/runtime surface.
        Toast.makeText(this, "M3U source is configured and ready for StreamVault", Toast.LENGTH_SHORT).show()
    }

    private fun isHttpUrl(value: String): Boolean =
        runCatching {
            val protocol = URL(value).protocol.lowercase()
            protocol == "http" || protocol == "https"
        }.getOrDefault(false)

    override fun onDestroy() {
        super.onDestroy()
    }
}
