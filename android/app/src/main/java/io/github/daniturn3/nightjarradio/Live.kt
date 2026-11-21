package io.github.daniturn3.nightjarradio

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Live station lists for Android Auto (popular, sport, moods, voice search), fetched from radio-browser.info. */
object Live {
    private val hosts = listOf("de1", "de2", "fi1", "all")
    private val at = HashMap<String, Long>()

    // id, title, radio-browser tag
    val MOODS = listOf(
        Triple("chill", "Chill", "chillout"), Triple("jazz", "Jazz", "jazz"), Triple("80s", "80s", "80s"),
        Triple("90s", "90s", "90s"), Triple("rock", "Rock", "rock"), Triple("pop", "Pop", "pop"),
        Triple("dance", "Dance", "dance"), Triple("classical", "Classical", "classical"), Triple("country", "Country", "country"),
        Triple("soul", "Soul", "soul"), Triple("lofi", "Lo-fi", "lofi"), Triple("news", "News & talk", "news")
    )

    const val GRID = 2
    const val LIST = 1
    private const val BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
    private const val PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"

    fun country(c: Context): String {
        val raw = c.getSharedPreferences("njr_keep", Context.MODE_PRIVATE).getString("country", null)
        val cc = raw?.trim('"', ' ')?.uppercase()
        return if (cc != null && cc.length == 2) cc else (Locale.getDefault().country.ifEmpty { "GB" })
    }

    fun countryName(cc: String): String = Locale("", cc).getDisplayCountry(Locale.UK).ifEmpty { cc }

    /** A browsable folder; children shown as a grid or list on the car screen. */
    fun folder(id: String, title: String, sub: String = "", style: Int = LIST): MediaItem {
        val ex = Bundle().apply { putInt(PLAYABLE_HINT, style); putInt(BROWSABLE_HINT, style) }
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setSubtitle(sub.ifEmpty { null }).setIsBrowsable(true).setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS).setExtras(ex).build()
        ).build()
    }

    private fun get(path: String): JSONArray? {
        for (h in hosts) {
            try {
                val c = URL("https://$h.api.radio-browser.info$path").openConnection() as HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 8000
                c.setRequestProperty("User-Agent", "NightjarRadio/1.0 (Android Auto)")
                if (c.responseCode != 200) { c.disconnect(); continue }
                val t = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                return JSONArray(t)
            } catch (_: Exception) { }
        }
        return null
    }

    private fun slim(a: JSONArray, max: Int): JSONArray {
        val out = JSONArray(); val seen = HashSet<String>()
        for (i in 0 until a.length()) {
            val x = a.optJSONObject(i) ?: continue
            val url = x.optString("url_resolved").ifEmpty { x.optString("url") }
            val name = x.optString("name").trim()
            if (!url.startsWith("http") || name.isEmpty() || x.optInt("lastcheckok", 1) == 0) continue
            if (!seen.add(name.lowercase())) continue
            val tags = JSONArray(); x.optString("tags").split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(3).forEach { tags.put(it) }
            out.put(JSONObject().put("u", x.optString("stationuuid")).put("n", name).put("url", url)
                .put("fav", x.optString("favicon")).put("tags", tags).put("c", x.optString("country"))
                .put("hls", x.optInt("hls") == 1))
            if (out.length() >= max) break
        }
        return out
    }

    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Fills (or refreshes, every 20 min) a live folder and saves it so items can be played and skipped through. */
    fun load(c: Context, folder: String): List<MediaItem> {
        val key = "live_$folder"
        val fresh = (System.currentTimeMillis() - (at[folder] ?: 0L)) < 20 * 60_000
        if (!fresh || Store.items(c, key).isEmpty()) {
            val cc = country(c)
            val path = when {
                folder == "popular" -> "/json/stations/search?countrycode=$cc&order=clickcount&reverse=true&hidebroken=true&limit=60"
                folder == "sport" -> "/json/stations/search?countrycode=$cc&tag=sports&order=clickcount&reverse=true&hidebroken=true&limit=60"
                folder.startsWith("mood_") -> {
                    val tag = MOODS.firstOrNull { it.first == folder.removePrefix("mood_") }?.third ?: return emptyList()
                    "/json/stations/search?tag=${q(tag)}&order=clickcount&reverse=true&hidebroken=true&limit=60"
                }
                else -> return Store.items(c, key)
            }
            var a = get(path)
            if (folder == "sport" && (a == null || a.length() < 5)) {
                a = get("/json/stations/search?countrycode=$cc&name=sport&order=clickcount&reverse=true&hidebroken=true&limit=40")
            }
            if (a != null) { Store.put(c, key, slim(a, 30).toString()); at[folder] = System.currentTimeMillis() }
        }
        return Store.items(c, key)
    }

    /** Voice / car search: forgiving name search, best match first. */
    fun search(c: Context, query: String): List<MediaItem> {
        val words = query.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(' ').filter { it.isNotBlank() }
        val core = words.filter { it !in setOf("radio", "fm", "am", "the", "station", "play", "on", "nightjar") }.ifEmpty { words }
        val tries = listOf(query.trim(), core.joinToString(" "), core.maxByOrNull { it.length } ?: "").filter { it.isNotBlank() }.distinct()
        val all = JSONArray(); val ids = HashSet<String>()
        for (t in tries) {
            val a = get("/json/stations/search?name=${q(t)}&order=clickcount&reverse=true&hidebroken=true&limit=40") ?: continue
            for (i in 0 until a.length()) { val o = a.optJSONObject(i) ?: continue; if (ids.add(o.optString("stationuuid"))) all.put(o) }
            if (all.length() >= 15) break
        }
        if (all.length() == 0) {
            // Maybe it was a genre ("play jazz")
            get("/json/stations/search?tag=${q(core.joinToString(" "))}&order=clickcount&reverse=true&hidebroken=true&limit=30")?.let {
                for (i in 0 until it.length()) all.put(it.optJSONObject(i))
            }
        }
        // Rank: names containing every word first
        val list = (0 until all.length()).mapNotNull { all.optJSONObject(it) }
        val ranked = list.sortedByDescending { o ->
            val n = o.optString("name").lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            (if (core.all { n.contains(it) }) 2 else 0) + (if (n.trim().startsWith(core.firstOrNull() ?: "#")) 1 else 0)
        }
        val arr = JSONArray(); ranked.forEach { arr.put(it) }
        Store.put(c, "live_search", slim(arr, 25).toString())
        return Store.items(c, "live_search")
    }
}
