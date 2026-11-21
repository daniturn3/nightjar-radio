package io.github.daniturn3.nightjarradio

import android.app.Activity
import android.net.Uri
import android.view.ContextThemeWrapper
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.app.MediaRouteControllerDialog
import com.google.android.gms.cast.framework.CastContext
import org.json.JSONObject

/**
 * Sends the current station to a Chromecast / Google speaker / Google TV.
 * Everything here fails quietly on phones without Google Play services.
 */
@UnstableApi
class Caster(private val act: Activity, private val emit: (JSONObject) -> Unit) {
    private var ctx: CastContext? = null
    private var player: CastPlayer? = null
    private var lastTitle = ""

    val available: Boolean get() = ctx != null
    val connected: Boolean get() = player?.isCastSessionAvailable == true

    fun start() {
        try {
            val c = CastContext.getSharedInstance(act)
            ctx = c
            val p = CastPlayer(c)
            player = p
            p.setSessionAvailabilityListener(object : SessionAvailabilityListener {
                override fun onCastSessionAvailable() = send(start = true)
                override fun onCastSessionUnavailable() = send(end = true)
            })
            p.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = send()
            })
        } catch (_: Throwable) { ctx = null; player = null }
    }

    fun release() { try { player?.setSessionAvailabilityListener(null); player?.release() } catch (_: Throwable) { } }

    private fun device(): String = try { ctx?.sessionManager?.currentCastSession?.castDevice?.friendlyName ?: "" } catch (_: Throwable) { "" }

    private fun send(start: Boolean = false, end: Boolean = false) {
        val p = player
        val o = JSONObject().put("on", connected).put("dev", device())
            .put("s", when { p == null || !connected -> "off"; p.isPlaying -> "playing"
                p.playWhenReady && p.playbackState == Player.STATE_BUFFERING -> "buffering"; else -> "paused" })
        if (start) o.put("start", true)
        if (end) o.put("end", true)
        emit(o)
    }

    /** Opens the "pick a device" list, or the "now casting / stop" panel when already connected. */
    fun pick() {
        val c = ctx ?: return
        try {
            val themed = ContextThemeWrapper(act, androidx.appcompat.R.style.Theme_AppCompat_NoActionBar)
            if (connected) MediaRouteControllerDialog(themed).show()
            else MediaRouteChooserDialog(themed).apply { routeSelector = c.mergedSelector ?: return@apply }.show()
        } catch (_: Throwable) { }
    }

    fun play(json: String) {
        val p = player ?: return
        if (!connected) return
        try {
            val o = JSONObject(json)
            val url = o.optString("url")
            if (!url.startsWith("http")) return
            lastTitle = o.optString("n")
            val mime = when {
                o.optBoolean("hls") || url.contains(".m3u8", true) -> MimeTypes.APPLICATION_M3U8
                url.contains("aac", true) -> MimeTypes.AUDIO_AAC
                url.contains("ogg", true) -> MimeTypes.AUDIO_OGG
                else -> MimeTypes.AUDIO_MPEG
            }
            val md = MediaMetadata.Builder().setTitle(lastTitle).setArtist("Nightjar Radio")
                .apply { o.optString("fav").takeIf { it.startsWith("https") }?.let { setArtworkUri(Uri.parse(it)) } }.build()
            val item = MediaItem.Builder().setUri(url).setMimeType(mime).setMediaMetadata(md).build()
            p.setMediaItem(item); p.prepare(); p.play()
        } catch (_: Throwable) { }
    }

    fun toggle() { val p = player ?: return; if (p.isPlaying) p.pause() else { if (p.playbackState == Player.STATE_IDLE) p.prepare(); p.play() } }
    fun stop() { try { ctx?.sessionManager?.endCurrentSession(true) } catch (_: Throwable) { } }
}
