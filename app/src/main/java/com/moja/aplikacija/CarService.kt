package com.moja.aplikacija

import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

class CarService : CarAppService() {
    override fun createHostValidator() = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = TvScreen(carContext)
    }
}

/** WebView koji se crta na virtualni ekran (a taj na ekran auta). */
class YtPresentation(ctx: Context, display: Display) : Presentation(ctx, display) {
    lateinit var web: WebView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
            loadUrl("https://m.youtube.com")
        }
        setContentView(web)
    }
}

class TvScreen(ctx: CarContext) : Screen(ctx) {
    private val channels: List<Pair<String, String>> =
        File(ctx.filesDir, "list.m3u").takeIf { it.exists() }
            ?.let { M3u.parse(it.readText()) } ?: emptyList()
    private var index = 0
    private var paused = false
    private var youtube = false
    private var container: SurfaceContainer? = null
    private var vd: VirtualDisplay? = null
    private var pres: YtPresentation? = null
    private val player: ExoPlayer = ExoPlayer.Builder(ctx).build()

    init {
        ctx.getCarService(AppManager::class.java).setSurfaceCallback(object : SurfaceCallback {
            override fun onSurfaceAvailable(c: SurfaceContainer) {
                container = c
                applyMode()
            }
            override fun onSurfaceDestroyed(c: SurfaceContainer) {
                teardownYt()
                player.setVideoSurface(null)
                container = null
            }
            override fun onClick(x: Float, y: Float) {
                val w = pres?.web ?: return
                val t = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
                val up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0)
                w.dispatchTouchEvent(down)
                w.dispatchTouchEvent(up)
                down.recycle(); up.recycle()
            }
            override fun onScroll(distanceX: Float, distanceY: Float) {
                pres?.web?.scrollBy(distanceX.toInt(), distanceY.toInt())
            }
        })
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                teardownYt(); player.release()
            }
        })
    }

    private fun applyMode() {
        val c = container ?: return
        val s = c.surface ?: return
        if (youtube) {
            player.pause()
            player.setVideoSurface(null)
            teardownYt()
            val dm = carContext.getSystemService(DisplayManager::class.java)
            vd = dm.createVirtualDisplay(
                "yt", c.width, c.height, c.dpi, s,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            )
            pres = YtPresentation(carContext, vd!!.display).also { it.show() }
        } else {
            teardownYt()
            player.setVideoSurface(s)
            playCurrent()
        }
        invalidate()
    }

    private fun teardownYt() {
        pres?.dismiss(); pres = null
        vd?.release(); vd = null
    }

    private fun playCurrent() {
        if (channels.isEmpty()) return
        player.setMediaItem(MediaItem.fromUri(channels[index].second))
        player.prepare()
        player.playWhenReady = !paused
    }

    private fun go(delta: Int) {
        if (channels.isEmpty()) return
        index = (index + delta + channels.size) % channels.size
        paused = false
        playCurrent()
        invalidate()
    }

    private fun act(title: String, f: () -> Unit) =
        Action.Builder().setTitle(title).setOnClickListener { f() }.build()

    override fun onGetTemplate(): Template {
        val b = NavigationTemplate.Builder()
        if (youtube) {
            b.setNavigationInfo(MessageInfo.Builder("YouTube").build())
            b.setActionStrip(
                ActionStrip.Builder()
                    .addAction(act("◀ Nazad") { pres?.web?.goBack() })
                    .addAction(act("Početna") { pres?.web?.loadUrl("https://m.youtube.com") })
                    .addAction(act("TV") { youtube = false; applyMode() })
                    .build()
            )
            if (carContext.carAppApiLevel >= 2) {
                b.setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
            }
        } else {
            val title = if (channels.isEmpty()) "Nema kanala. Dodaj M3U u telefonu."
            else "${index + 1}/${channels.size}  ${channels[index].first}"
            b.setNavigationInfo(MessageInfo.Builder(title).build())
            b.setActionStrip(
                ActionStrip.Builder()
                    .addAction(act("◀") { go(-1) })
                    .addAction(act(if (paused) "▶ Pusti" else "⏸ Pauza") {
                        paused = !paused; player.playWhenReady = !paused; invalidate()
                    })
                    .addAction(act("▶") { go(1) })
                    .addAction(act("YouTube") { youtube = true; applyMode() })
                    .build()
            )
        }
        return b.build()
    }
}
