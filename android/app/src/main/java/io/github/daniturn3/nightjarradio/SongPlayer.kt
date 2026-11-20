package io.github.daniturn3.nightjarradio

import android.os.Bundle
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.metadata.icy.IcyInfo

/** Shows the song a station is broadcasting (ICY "StreamTitle") as the title, with the station underneath. */
@UnstableApi
class SongPlayer(exo: ExoPlayer) : ForwardingPlayer(exo) {
    var song: String? = null
        private set
    private val listeners = mutableListOf<Player.Listener>()

    init {
        exo.addListener(object : Player.Listener {
            override fun onMetadata(metadata: Metadata) {
                for (i in 0 until metadata.length()) {
                    val e = metadata.get(i)
                    if (e is IcyInfo) {
                        val t = e.title?.trim()?.trim('-', ' ')?.takeIf { it.length > 1 }
                        if (t != song) { song = t; announce() }
                    }
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (song != null) { song = null; announce() }
            }
        })
    }

    private fun announce() {
        val m = mediaMetadata
        listeners.toList().forEach { it.onMediaMetadataChanged(m) }
    }

    override fun addListener(listener: Player.Listener) {
        super.addListener(listener); listeners.add(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listener); listeners.remove(listener)
    }

    override fun getMediaMetadata(): MediaMetadata {
        val base = super.getMediaMetadata()
        val s = song
        val station = currentMediaItem?.mediaMetadata?.title
        val b = base.buildUpon().setExtras(Bundle().apply { putString("song", s ?: "") })
        if (s != null) b.setTitle(s).setArtist(station)
        return b.build()
    }
}
