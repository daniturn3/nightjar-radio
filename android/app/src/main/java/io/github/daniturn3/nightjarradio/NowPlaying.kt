package io.github.daniturn3.nightjarradio

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Peeks at a station's stream just long enough to read the song it's sending (ICY "StreamTitle"),
 * then hangs up. Typically 10–60 KB per station. Returns "" when the station doesn't send titles.
 */
object NowPlaying {
    private val pool = Executors.newFixedThreadPool(4)
    private const val MAX_METAINT = 65_536

    fun peekAll(urls: List<String>, done: (String, String) -> Unit) {
        urls.forEach { u -> pool.execute { done(u, try { peek(u, 0) } catch (_: Throwable) { "" }) } }
    }

    private fun open(u: String): HttpURLConnection {
        var url = URL(u)
        repeat(4) {
            if (url.protocol != "http" && url.protocol != "https") throw IllegalArgumentException()
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 5000; c.readTimeout = 5000
            c.setRequestProperty("Icy-MetaData", "1")
            c.setRequestProperty("User-Agent", "NightjarRadio/1.0 (Android)")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IllegalStateException()
                c.disconnect(); url = URL(url, loc); return@repeat
            }
            return c
        }
        throw IllegalStateException()
    }

    private fun peek(u: String, depth: Int): String {
        if (u.contains(".m3u8", true)) return ""
        val c = open(u)
        try {
            val type = (c.contentType ?: "").lowercase()
            if (depth == 0 && (type.contains("mpegurl") || type.contains("scpls") || u.endsWith(".m3u", true) || u.endsWith(".pls", true))) {
                val bb = ByteArray(16_384); var n = 0; val pin = c.inputStream
                while (n < bb.size) { val r = pin.read(bb, n, bb.size - n); if (r < 0) break; n += r }
                val body = String(bb, 0, n, Charsets.UTF_8)
                val next = body.lines().map { it.trim().substringAfter("=", it.trim()) }.firstOrNull { it.startsWith("http") } ?: return ""
                c.disconnect(); return peek(next, 1)
            }
            val mi = c.getHeaderField("icy-metaint")?.trim()?.toIntOrNull() ?: return ""
            if (mi <= 0 || mi > MAX_METAINT) return ""
            val ins = c.inputStream
            repeat(2) {
                skipFully(ins, mi.toLong())
                val len = ins.read()
                if (len < 0) return ""
                if (len > 0) {
                    val b = ByteArray(len * 16); readFully(ins, b)
                    return title(b)
                }
                if (mi > 16_384) return ""
            }
            return ""
        } finally { c.disconnect() }
    }

    private fun skipFully(i: InputStream, n: Long) {
        var left = n; val buf = ByteArray(8192)
        while (left > 0) { val r = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (r < 0) throw IllegalStateException(); left -= r }
    }

    private fun readFully(i: InputStream, b: ByteArray) {
        var off = 0
        while (off < b.size) { val r = i.read(b, off, b.size - off); if (r < 0) break; off += r }
    }

    private fun title(b: ByteArray): String {
        var s = String(b, Charsets.UTF_8)
        if (s.contains('�')) s = String(b, Charsets.ISO_8859_1)
        val m = Regex("StreamTitle='(.*?)';", RegexOption.DOT_MATCHES_ALL).find(s) ?: return ""
        return m.groupValues[1].trim().trim('-', ' ').take(160)
    }
}
