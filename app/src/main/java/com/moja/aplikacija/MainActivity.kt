package com.moja.aplikacija

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import java.net.URL

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var tvPanel: LinearLayout
    private lateinit var list: ListView
    private lateinit var playerView: PlayerView
    private lateinit var root: FrameLayout
    private var player: ExoPlayer? = null
    private var channels = listOf<Pair<String, String>>()
    private var customView: View? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val text = contentResolver.openInputStream(it)?.bufferedReader()?.readText() ?: ""
            saveAndLoad(text)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(btn("YouTube") { show(true) })
        bar.addView(btn("TV") { show(false) })
        bar.addView(btn("+ M3U") { addM3u() })
        column.addView(bar)

        val body = FrameLayout(this)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(v: View, cb: CustomViewCallback) {
                    customView = v
                    root.addView(v, FrameLayout.LayoutParams(-1, -1))
                }
                override fun onHideCustomView() {
                    customView?.let { root.removeView(it) }
                    customView = null
                }
            }
            loadUrl("https://m.youtube.com")
        }
        playerView = PlayerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, (230 * dp).toInt())
        }
        list = ListView(this)
        tvPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(playerView)
            addView(list)
        }
        body.addView(web, FrameLayout.LayoutParams(-1, -1))
        body.addView(tvPanel, FrameLayout.LayoutParams(-1, -1))
        column.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))

        root = FrameLayout(this)
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        list.setOnItemClickListener { _, _, pos, _ -> play(channels[pos].second) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> web.webChromeClient?.onHideCustomView()
                    web.visibility == View.VISIBLE && web.canGoBack() -> web.goBack()
                    else -> finish()
                }
            }
        })

        File(filesDir, "list.m3u").takeIf { it.exists() }?.let { parse(it.readText()) }
    }

    private fun show(youtube: Boolean) {
        web.visibility = if (youtube) View.VISIBLE else View.GONE
        tvPanel.visibility = if (youtube) View.GONE else View.VISIBLE
        if (youtube) player?.pause()
    }

    private fun addM3u() {
        val opts = arrayOf("Zalijepi link (URL)", "Odaberi datoteku s telefona")
        AlertDialog.Builder(this).setTitle("Dodaj M3U listu").setItems(opts) { _, i ->
            if (i == 1) pickFile.launch("*/*") else {
                val input = EditText(this).apply { hint = "https://.../lista.m3u" }
                AlertDialog.Builder(this).setTitle("M3U link").setView(input)
                    .setPositiveButton("Učitaj") { _, _ ->
                        val link = input.text.toString().trim()
                        Thread {
                            try {
                                val text = URL(link).readText()
                                runOnUiThread { saveAndLoad(text) }
                            } catch (e: Exception) {
                                runOnUiThread { Toast.makeText(this, "Greška: ${e.message}", Toast.LENGTH_LONG).show() }
                            }
                        }.start()
                    }.setNegativeButton("Odustani", null).show()
            }
        }.show()
    }

    private fun saveAndLoad(text: String) {
        File(filesDir, "list.m3u").writeText(text)
        parse(text)
        show(false)
    }

    private fun parse(text: String) {
        val out = mutableListOf<Pair<String, String>>()
        var name = ""
        for (raw in text.lines()) {
            val l = raw.trim()
            if (l.startsWith("#EXTINF")) name = l.substringAfterLast(",").trim()
            else if (l.isNotEmpty() && !l.startsWith("#")) {
                out.add((if (name.isEmpty()) l else name) to l); name = ""
            }
        }
        channels = out
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, out.map { it.first })
        Toast.makeText(this, "Učitano kanala: ${out.size}", Toast.LENGTH_SHORT).show()
    }

    private fun play(url: String) {
        if (player == null) {
            player = ExoPlayer.Builder(this).build()
            playerView.player = player
        }
        player!!.setMediaItem(MediaItem.fromUri(url))
        player!!.prepare()
        player!!.playWhenReady = true
    }

    override fun onStop() { super.onStop(); player?.pause() }
    override fun onDestroy() { super.onDestroy(); player?.release() }
}
