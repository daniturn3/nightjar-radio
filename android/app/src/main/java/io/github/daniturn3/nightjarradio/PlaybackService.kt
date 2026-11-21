package io.github.daniturn3.nightjarradio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.Player
import androidx.media3.datasource.RawResourceDataSource
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Executors

@UnstableApi
class PlaybackService : MediaLibraryService() {
    companion object {
        const val ACT_TOGGLE = "njr.toggle"
        const val ACT_NEXT = "njr.next"
        const val ACT_PREV = "njr.prev"
        const val ACT_PRESET = "njr.preset"
        const val ACT_ALARM = "njr.alarm"
    }

    private var session: MediaLibrarySession? = null
    private lateinit var song: SongPlayer
    private var fadeRun: Runnable? = null
    private var chimeRun: Runnable? = null
    private lateinit var exo: ExoPlayer
    private val h = Handler(Looper.getMainLooper())
    private var sleepRun: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("NightjarRadio/1.0 (Android)")
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
        exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        exo.repeatMode = Player.REPEAT_MODE_ALL
        song = SongPlayer(exo)
        session = MediaLibrarySession.Builder(this, song, Callback()).setSessionActivity(open).build()
        addSession(session!!)
        exo.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = saveWidget()
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) { playedId = exo.currentMediaItem?.mediaId; stopRetry(false) }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem?.mediaId != playedId) { playedId = null; stopRetry(false) }
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) stopRetry(false)
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) = maybeRetry(error)
        })
        watchNetwork()
        song.addListener(object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) = saveWidget()
        })
    }

    // ---- Auto-reconnect: if a station that was playing drops (signal loss, Wi-Fi <-> mobile switch), keep trying quietly. ----
    private val net = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(3))
    private var playedId: String? = null
    private var tries = 0
    private var retryRun: Runnable? = null
    private val waits = longArrayOf(2_000, 3_000, 5_000, 8_000, 12_000, 15_000, 20_000, 30_000, 30_000, 30_000, 30_000, 30_000)
    private var netCb: android.net.ConnectivityManager.NetworkCallback? = null

    private fun setReconnecting(on: Boolean) =
        getSharedPreferences("njr", Context.MODE_PRIVATE).edit().putBoolean("reconnecting", on).apply()

    private fun maybeRetry(e: androidx.media3.common.PlaybackException) {
        val item = exo.currentMediaItem ?: return
        if (item.mediaId == "chime" || !exo.playWhenReady) return
        val network = e.errorCode in 2000..2999 || e.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ||
            e.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_TIMEOUT
        // Only keep retrying a station that was actually playing — a dead station should fail fast.
        if (!network || playedId != item.mediaId || tries >= waits.size) { stopRetry(false); return }
        setReconnecting(true)
        val r = Runnable { retryNow() }
        retryRun?.let { h.removeCallbacks(it) }
        retryRun = r
        h.postDelayed(r, waits[tries++])
    }

    private fun retryNow() {
        retryRun?.let { h.removeCallbacks(it) }; retryRun = null
        if (exo.playerError == null || !exo.playWhenReady) return
        exo.seekToDefaultPosition()
        exo.prepare()
    }

    private fun stopRetry(keepFlag: Boolean) {
        retryRun?.let { h.removeCallbacks(it) }; retryRun = null
        tries = 0
        if (!keepFlag) setReconnecting(false)
    }

    private fun watchNetwork() {
        try {
            val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    // Connection is back — retry straight away instead of waiting.
                    h.post { if (retryRun != null) { h.postDelayed({ retryNow() }, 1200) } }
                }
            }
            cm.registerDefaultNetworkCallback(cb); netCb = cb
        } catch (_: Exception) { }
    }

    private var logoFor: String? = null

    /** Fetch the station's logo for the widgets (rounded, small). */
    private fun fetchLogo(item: MediaItem?) {
        val u = item?.mediaId?.substringAfter('/') ?: return
        if (item.mediaId == "chime" || u == logoFor) return
        logoFor = u
        val art = item.mediaMetadata.artworkUri?.toString()
        Thread {
            var bmp: Bitmap? = null
            try {
                if (art != null && art.startsWith("http")) {
                    val con = URL(art).openConnection() as HttpURLConnection
                    con.connectTimeout = 8000; con.readTimeout = 8000; con.instanceFollowRedirects = true
                    con.inputStream.use { bmp = BitmapFactory.decodeStream(it) }
                }
            } catch (_: Exception) {
            }
            try {
                val f = File(filesDir, "wlogo.png")
                val b = bmp
                if (b != null) {
                    val sz = 160
                    val out = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
                    val cv = Canvas(out)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                    cv.drawRoundRect(RectF(0f, 0f, sz.toFloat(), sz.toFloat()), 30f, 30f, paint)
                    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                    cv.drawBitmap(Bitmap.createScaledBitmap(b, sz, sz, true), 0f, 0f, paint)
                    FileOutputStream(f).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } else f.delete()
                getSharedPreferences("njr", Context.MODE_PRIVATE).edit().putString("w_logo_u", u).putString("lastU", u).apply()
            } catch (_: Exception) {
            }
            h.post { RadioWidget.refresh(this) }
        }.start()
    }

    private fun saveWidget() {
        val item = exo.currentMediaItem
        fetchLogo(item)
        val chime = item?.mediaId == "chime"
        if (!chime && exo.repeatMode != Player.REPEAT_MODE_ALL) exo.repeatMode = Player.REPEAT_MODE_ALL
        val p = getSharedPreferences("njr", Context.MODE_PRIVATE)
        val station = if (chime) "Wake-up alarm" else item?.mediaMetadata?.title?.toString()
        val playing = exo.isPlaying || (exo.playWhenReady && exo.playbackState == Player.STATE_BUFFERING)
        val s = song.song ?: ""
        if (station == p.getString("w_station", null) && s == p.getString("w_song", "") && playing == p.getBoolean("w_playing", false)) return
        val e = p.edit().putString("w_song", s).putBoolean("w_playing", playing)
        if (station != null) e.putString("w_station", station)
        if (item != null && !chime) e.putString("lastU", item.mediaId.substringAfter('/'))
        e.apply()
        RadioWidget.refresh(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val r = super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACT_TOGGLE -> if (exo.playWhenReady && exo.mediaItemCount > 0) exo.pause() else if (!startFresh()) bail()
            ACT_NEXT, ACT_PREV -> {
                if (exo.mediaItemCount == 0 && !loadSaved()) bail() else {
                    if (intent.action == ACT_NEXT) exo.seekToNextMediaItem() else exo.seekToPreviousMediaItem()
                    exo.prepare(); exo.play()
                }
            }
            ACT_PRESET -> {
                val all = Store.items(this, "presets")
                val i = intent.getIntExtra("i", 0)
                if (i in all.indices) { exo.setMediaItems(all, i, C.TIME_UNSET); exo.prepare(); exo.play() } else bail()
            }
            ACT_ALARM -> ring()
        }
        return r
    }

    /** Load the last list you were listening to, at the last station. */
    private fun loadSaved(): Boolean {
        var items = Store.items(this, "queue")
        if (items.isEmpty()) items = Store.items(this, "presets")
        if (items.isEmpty()) items = Store.items(this, "recent")
        if (items.isEmpty()) return false
        val last = getSharedPreferences("njr", Context.MODE_PRIVATE).getString("lastU", "")
        val i = items.indexOfFirst { it.mediaId.substringAfter('/') == last }.coerceAtLeast(0)
        exo.setMediaItems(items, i, C.TIME_UNSET)
        return true
    }

    private fun startFresh(): Boolean {
        if (exo.mediaItemCount == 0 && !loadSaved()) return false
        exo.stop(); exo.prepare(); exo.play()
        return true
    }

    /** Nothing to play: satisfy Android's foreground-start rule briefly, then stop. */
    private fun bail() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("njr_info", "Info", NotificationManager.IMPORTANCE_LOW))
            val n: Notification = NotificationCompat.Builder(this, "njr_info")
                .setSmallIcon(androidx.media3.session.R.drawable.media3_notification_small_icon)
                .setContentTitle("Nightjar Radio").setContentText("Open the app and pick a station first").build()
            ServiceCompat.startForeground(this, 99, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
    }

    private fun ring() {
        val cfg = Alarm.config(this)
        Alarm.rang(this)
        val st = cfg?.optJSONObject("st")
        val target = (cfg?.optDouble("vol", 0.7) ?: 0.7).toFloat().coerceIn(0.1f, 1f)
        fadeRun?.let { h.removeCallbacks(it) }; chimeRun?.let { h.removeCallbacks(it) }
        if (st == null) { chime(target); return }
        exo.setMediaItems(listOf(Store.item(st, "alarm/")), 0, C.TIME_UNSET)
        exo.volume = 0.03f
        exo.prepare(); exo.play()
        var k = 0
        val fade = object : Runnable {
            override fun run() {
                k++
                exo.volume = target * (k / 40f).coerceAtMost(1f)
                if (k < 40 && exo.playWhenReady) h.postDelayed(this, 1500) else fadeRun = null
            }
        }
        fadeRun = fade; h.postDelayed(fade, 1500)
        // If the station can't be reached (no internet, station down), ring a gentle chime instead.
        val backup = Runnable { if (!exo.isPlaying && exo.playWhenReady) chime(target) }
        chimeRun = backup; h.postDelayed(backup, 25_000)
    }

    private fun chime(vol: Float) {
        fadeRun?.let { h.removeCallbacks(it) }
        val item = MediaItem.Builder().setMediaId("chime").setUri(RawResourceDataSource.buildRawResourceUri(R.raw.chime))
            .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle("Wake-up alarm").setArtist("Nightjar Radio").build()).build()
        exo.setMediaItems(listOf(item), 0, 0L)
        exo.repeatMode = Player.REPEAT_MODE_ONE
        exo.volume = vol
        exo.prepare(); exo.play()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!exo.playWhenReady || exo.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        try { netCb?.let { getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } } catch (_: Exception) { }
        retryRun?.let { h.removeCallbacks(it) }; setReconnecting(false)
        sleepRun?.let { h.removeCallbacks(it) }
        getSharedPreferences("njr", Context.MODE_PRIVATE).edit().putBoolean("w_playing", false).apply()
        RadioWidget.refresh(this)
        session?.release()
        exo.release()
        session = null
        super.onDestroy()
    }

    private fun sleep(min: Int) {
        sleepRun?.let { h.removeCallbacks(it) }
        sleepRun = null
        if (min <= 0) return
        val r = Runnable {
            val v0 = exo.volume
            var step = 0
            val fade = object : Runnable {
                override fun run() {
                    step++
                    exo.volume = v0 * (1f - step / 20f)
                    if (step < 20) h.postDelayed(this, 300) else { exo.pause(); exo.volume = v0 }
                }
            }
            h.post(fade)
            sleepRun = null
        }
        sleepRun = r
        h.postDelayed(r, min * 60_000L)
    }

    private inner class Callback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val cmds = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_SLEEP, Bundle.EMPTY)).build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(cmds).build()
        }

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == CMD_SLEEP) sleep(args.getInt("min"))
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(Store.folder("root", "Nightjar Radio"),
                LibraryParams.Builder().setExtras(Bundle().apply {
                    putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
                    putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", Live.LIST)
                    putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", Live.LIST)
                }).build()))

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val it = Store.find(this@PlaybackService, mediaId)
            return Futures.immediateFuture(
                if (it != null) LibraryResult.ofItem(it, null) else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val c = this@PlaybackService
            if (parentId == "root") {
                val cc = Live.country(c)
                val list = mutableListOf<MediaItem>()
                if (Store.items(c, "presets").isNotEmpty()) list += Live.folder("presets", "Presets", "Your six quick-tune stations", Live.GRID)
                if (Store.items(c, "favs").isNotEmpty()) list += Live.folder("favs", "Favourites", "Stations you’ve starred")
                if (Store.items(c, "recent").isNotEmpty()) list += Live.folder("recent", "Recently played")
                list += Live.folder("live_popular", "Popular in ${Live.countryName(cc)}", "Top stations right now")
                list += Live.folder("moods", "Moods", "Chill, jazz, 80s, rock and more", Live.GRID)
                list += Live.folder("live_sport", "Sport", "Commentary and sports talk")
                return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(list), params))
            }
            if (parentId == "moods") {
                val list = Live.MOODS.map { (id, title, _) -> Live.folder("live_mood_$id", title, "", Live.LIST) }
                return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(list), params))
            }
            if (parentId.startsWith("live_") && parentId != "live_search") {
                return net.submit<LibraryResult<ImmutableList<MediaItem>>> {
                    LibraryResult.ofItemList(ImmutableList.copyOf(Live.load(c, parentId.removePrefix("live_"))), params)
                }
            }
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(Store.items(c, parentId)), params))
        }

        // ---- Search: the car's search button and "Hey Google, play … on Nightjar Radio" ----
        override fun onSearch(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            val c = this@PlaybackService
            net.submit { val n = Live.search(c, query).size; h.post { session.notifySearchResultChanged(browser, query, n, params) } }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String,
            page: Int, pageSize: Int, params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val c = this@PlaybackService
            return net.submit<LibraryResult<ImmutableList<MediaItem>>> {
                var r = Store.items(c, "live_search")
                if (r.isEmpty()) r = Live.search(c, query)
                LibraryResult.ofItemList(ImmutableList.copyOf(r), params)
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            mediaItems.firstOrNull()?.requestMetadata?.searchQuery?.let { sq ->
                val c = this@PlaybackService
                return net.submit<MutableList<MediaItem>> { Live.search(c, sq).take(1).toMutableList() }
            }
            return Futures.immediateFuture(mediaItems.mapNotNull { Store.resolve(this@PlaybackService, it) }.toMutableList())
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
            startIndex: Int, startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // "Hey Google, play Kerrang on Nightjar Radio" arrives as a search query.
            val sq = mediaItems.firstOrNull()?.requestMetadata?.searchQuery
            if (sq != null) {
                val c = this@PlaybackService
                return net.submit<MediaSession.MediaItemsWithStartPosition> {
                    var r = if (sq.isBlank()) Store.items(c, "presets").ifEmpty { Store.items(c, "recent") } else Live.search(c, sq)
                    if (r.isEmpty()) r = Store.items(c, "recent")
                    MediaSession.MediaItemsWithStartPosition(r, 0, C.TIME_UNSET)
                }
            }
            // Picked one station in a folder (e.g. on the car screen)? Load the whole folder so next/previous work.
            if (mediaItems.size == 1 && mediaItems[0].requestMetadata.mediaUri == null && mediaItems[0].localConfiguration == null) {
                val id = mediaItems[0].mediaId
                val folder = id.substringBefore('/', "")
                if (folder.isNotEmpty() && folder != "q") {
                    val all = Store.items(this@PlaybackService, folder)
                    val i = all.indexOfFirst { it.mediaId == id }
                    if (i >= 0) return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(all, i, C.TIME_UNSET))
                }
            }
            val res = mediaItems.mapNotNull { Store.resolve(this@PlaybackService, it) }
            val idx = if (startIndex == C.INDEX_UNSET || res.isEmpty()) 0 else startIndex.coerceIn(0, res.size - 1)
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(res, idx, C.TIME_UNSET))
        }
    }
}
