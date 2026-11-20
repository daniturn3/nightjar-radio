package io.github.daniturn3.nightjarradio

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import org.json.JSONArray
import org.json.JSONObject

const val CMD_SLEEP = "njr.sleep"

object Store {
    private fun prefs(c: Context) = c.getSharedPreferences("njr", Context.MODE_PRIVATE)

    fun put(c: Context, key: String, json: String) {
        prefs(c).edit().putString(key, json).apply()
    }

    private fun arr(c: Context, key: String): JSONArray =
        try { JSONArray(prefs(c).getString(key, "[]") ?: "[]") } catch (e: Exception) { JSONArray() }

    fun names(c: Context, folder: String): List<String> {
        val a = arr(c, folder)
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("n")?.takeIf { n -> n.isNotEmpty() } }
    }

    fun items(c: Context, folder: String): List<MediaItem> {
        val a = arr(c, folder)
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { item(it, "$folder/") } }
    }

    fun find(c: Context, id: String): MediaItem? {
        val u = id.substringAfter('/')
        val prefix = if (id.contains('/')) id.substringBefore('/') + "/" else ""
        for (k in listOf("queue", "presets", "favs", "recent")) {
            val a = arr(c, k)
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                if (o.optString("u") == u) return item(o, prefix)
            }
        }
        return null
    }

    fun item(o: JSONObject, prefix: String): MediaItem {
        val url = o.optString("url")
        val name = o.optString("n")
        val tag = o.optJSONArray("tags")?.optString(0) ?: ""
        val sub = listOf(o.optString("c"), tag).filter { it.isNotEmpty() }.joinToString(" · ")
        val hls = o.optBoolean("hls")
        val md = MediaMetadata.Builder()
            .setTitle(name)
            .setArtist(sub)
            .setAlbumTitle("Nightjar Radio")
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
        val fav = o.optString("fav")
        if (fav.startsWith("http")) md.setArtworkUri(Uri.parse(fav))
        val extras = Bundle().apply { putBoolean("hls", hls) }
        return MediaItem.Builder()
            .setMediaId(prefix + o.optString("u"))
            .setUri(url)
            .setMimeType(if (hls) MimeTypes.APPLICATION_M3U8 else null)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(url)).setExtras(extras).build())
            .setMediaMetadata(md.build())
            .build()
    }

    fun folder(id: String, title: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS).build()
        ).build()

    /** Turn whatever a controller sent us into a playable item. */
    fun resolve(c: Context, it: MediaItem): MediaItem? {
        if (it.localConfiguration != null) return it
        val uri = it.requestMetadata.mediaUri
        if (uri != null) {
            val hls = it.requestMetadata.extras?.getBoolean("hls") == true
            return it.buildUpon().setUri(uri).setMimeType(if (hls) MimeTypes.APPLICATION_M3U8 else null).build()
        }
        return find(c, it.mediaId)
    }
}
