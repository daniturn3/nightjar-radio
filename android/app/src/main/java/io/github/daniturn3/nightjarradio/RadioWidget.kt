package io.github.daniturn3.nightjarradio

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.media3.common.util.UnstableApi
import java.io.File

/** Classic presets widget. */
@UnstableApi
class RadioWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) = refresh(context)

    companion object {
        fun refresh(c: Context) = Widgets.refreshAll(c)
    }
}

@UnstableApi
class MiniWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) = Widgets.refreshAll(context)
}

@UnstableApi
class BarWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) = Widgets.refreshAll(context)
}

@UnstableApi
class FunWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) = Widgets.refreshAll(context)
}

@UnstableApi
object Widgets {
    private val PRESETS = intArrayOf(R.id.w_p1, R.id.w_p2, R.id.w_p3, R.id.w_p4, R.id.w_p5, R.id.w_p6)

    private class St(c: Context) {
        val p = c.getSharedPreferences("njr", Context.MODE_PRIVATE)
        val playing = p.getBoolean("w_playing", false)
        val song = p.getString("w_song", "") ?: ""
        val station: String? = p.getString("w_station", null)
        val names = Store.names(c, "presets")
        val logo = File(c.filesDir, "wlogo.png").takeIf { it.exists() && p.getString("w_logo_u", "") == p.getString("lastU", "-") }
        fun line(empty: String) = when {
            song.isNotEmpty() -> "♪ $song"
            playing -> "● Live"
            station != null -> "Paused"
            else -> empty
        }
    }

    fun refreshAll(c: Context) {
        val st = try { St(c) } catch (e: Exception) { return }
        update(c, RadioWidget::class.java) { presets(c, st) }
        update(c, MiniWidget::class.java) { mini(c, st) }
        update(c, BarWidget::class.java) { bar(c, st) }
        update(c, FunWidget::class.java) { fun_(c, st) }
    }

    private fun update(c: Context, cls: Class<*>, build: () -> RemoteViews) {
        try {
            val m = AppWidgetManager.getInstance(c)
            val ids = m.getAppWidgetIds(ComponentName(c, cls))
            if (ids.isNotEmpty()) m.updateAppWidget(ids, build())
        } catch (_: Exception) {
        }
    }

    private fun open(c: Context, act: String? = null): PendingIntent {
        val i = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (act != null) i.action = "njr.do.$act"
        return PendingIntent.getActivity(c, 500 + (act?.hashCode() ?: 0) % 1000, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun action(c: Context, act: String, i: Int, running: Boolean): PendingIntent {
        val it = Intent(c, PlaybackService::class.java).setAction(act).putExtra("i", i)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val rc = act.hashCode() + i
        return if (Build.VERSION.SDK_INT >= 26 && !(running && act == PlaybackService.ACT_TOGGLE))
            PendingIntent.getForegroundService(c, rc, it, flags)
        else PendingIntent.getService(c, rc, it, flags)
    }

    private fun controls(c: Context, v: RemoteViews, st: St, prev: Boolean = true, next: Boolean = true) {
        v.setImageViewResource(R.id.w_play, if (st.playing) R.drawable.ic_pause else R.drawable.ic_play)
        v.setOnClickPendingIntent(R.id.w_play, action(c, PlaybackService.ACT_TOGGLE, 0, st.playing))
        if (prev) v.setOnClickPendingIntent(R.id.w_prev, action(c, PlaybackService.ACT_PREV, 0, st.playing))
        if (next) v.setOnClickPendingIntent(R.id.w_next, action(c, PlaybackService.ACT_NEXT, 0, st.playing))
    }

    private fun logo(v: RemoteViews, st: St) {
        val f = st.logo
        val bmp = try { f?.let { BitmapFactory.decodeFile(it.path) } } catch (e: Exception) { null }
        if (bmp != null) v.setImageViewBitmap(R.id.w_logo, bmp) else v.setImageViewResource(R.id.w_logo, R.mipmap.ic_launcher)
    }

    private fun presets(c: Context, st: St): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_radio)
        v.setTextViewText(R.id.w_station, st.station ?: "Nightjar Radio")
        v.setTextViewText(R.id.w_song, st.line("Tap a preset to tune in"))
        controls(c, v, st)
        v.setOnClickPendingIntent(R.id.w_open, open(c))
        v.setOnClickPendingIntent(R.id.w_text, open(c))
        PRESETS.forEachIndexed { i, id ->
            val n = st.names.getOrNull(i)
            if (n == null) {
                val first = i == 0 && st.names.isEmpty()
                v.setViewVisibility(id, if (first) View.VISIBLE else View.INVISIBLE)
                if (first) { v.setTextViewText(id, "Save presets in the app"); v.setOnClickPendingIntent(id, open(c)) }
            } else {
                v.setViewVisibility(id, View.VISIBLE)
                v.setTextViewText(id, n)
                v.setOnClickPendingIntent(id, action(c, PlaybackService.ACT_PRESET, i, st.playing))
            }
        }
        return v
    }

    private fun mini(c: Context, st: St): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_mini)
        v.setTextViewText(R.id.w_station, st.station ?: "Nightjar Radio")
        v.setTextViewText(R.id.w_song, st.line("Tap a number to tune in"))
        v.setTextViewText(R.id.w_live, if (st.playing) "● LIVE" else "")
        logo(v, st)
        controls(c, v, st)
        v.setOnClickPendingIntent(R.id.w_text, open(c))
        PRESETS.forEachIndexed { i, id ->
            if (i < st.names.size) {
                v.setTextColor(id, 0xFFF3ECDF.toInt())
                v.setOnClickPendingIntent(id, action(c, PlaybackService.ACT_PRESET, i, st.playing))
            } else {
                v.setTextColor(id, 0x55A9ADBA)
                v.setOnClickPendingIntent(id, open(c))
            }
        }
        return v
    }

    private fun bar(c: Context, st: St): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_bar)
        v.setTextViewText(R.id.w_station, st.station ?: "Nightjar Radio")
        v.setTextViewText(R.id.w_song, st.line("Tap play to tune in"))
        logo(v, st)
        controls(c, v, st, prev = false)
        v.setOnClickPendingIntent(R.id.w_text, open(c))
        v.setOnClickPendingIntent(R.id.w_logo, open(c))
        return v
    }

    private fun fun_(c: Context, st: St): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_fun)
        v.setOnClickPendingIntent(R.id.f_globe, open(c, "globe"))
        v.setOnClickPendingIntent(R.id.f_dial, open(c, "dial"))
        v.setOnClickPendingIntent(R.id.f_bed, open(c, "bed"))
        v.setImageViewResource(R.id.w_play, if (st.playing) R.drawable.ic_pause else R.drawable.ic_play)
        v.setTextViewText(R.id.f_playtx, if (st.playing) "Pause" else "Play")
        v.setOnClickPendingIntent(R.id.f_play, action(c, PlaybackService.ACT_TOGGLE, 0, st.playing))
        return v
    }
}
