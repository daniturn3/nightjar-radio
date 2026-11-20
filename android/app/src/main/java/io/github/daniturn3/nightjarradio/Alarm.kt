package io.github.daniturn3.nightjarradio

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.media3.common.util.UnstableApi
import org.json.JSONObject
import java.util.Calendar

@UnstableApi
object Alarm {
    fun config(c: Context): JSONObject? {
        val raw = c.getSharedPreferences("njr", Context.MODE_PRIVATE).getString("alarm", null) ?: return null
        return try { JSONObject(raw) } catch (e: Exception) { null }
    }

    private fun pending(c: Context): PendingIntent {
        val it = Intent(c, PlaybackService::class.java).setAction(PlaybackService.ACT_ALARM)
        val f = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return if (Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(c, 77, it, f) else PendingIntent.getService(c, 77, it, f)
    }

    /** Schedules the next ring. Returns its time, or 0 when the alarm is off. */
    fun schedule(c: Context): Long {
        val am = c.getSystemService(AlarmManager::class.java) ?: return 0
        val pi = pending(c)
        am.cancel(pi)
        val cfg = config(c) ?: return 0
        if (!cfg.optBoolean("on")) return 0
        val t = next(cfg) ?: return 0
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        } else {
            val show = PendingIntent.getActivity(c, 78, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            am.setAlarmClock(AlarmManager.AlarmClockInfo(t, show), pi)
        }
        return t
    }

    fun next(cfg: JSONObject, from: Long = System.currentTimeMillis()): Long? {
        val days = cfg.optJSONArray("days")
        val set = (0 until (days?.length() ?: 0)).map { days!!.optInt(it) }.toSet()
        val cal = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, cfg.optInt("h", 7)); set(Calendar.MINUTE, cfg.optInt("m", 0))
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= from + 1000) cal.add(Calendar.DAY_OF_YEAR, 1)
        repeat(8) {
            val jsDay = cal.get(Calendar.DAY_OF_WEEK) - 1 // 0 = Sunday, like JavaScript
            if (set.isEmpty() || jsDay in set) return cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    /** Called when it rings: a one-off alarm switches itself off, a repeating one books the next day. */
    fun rang(c: Context) {
        val cfg = config(c) ?: return
        val days = cfg.optJSONArray("days")
        if (days == null || days.length() == 0) {
            cfg.put("on", false)
            Store.put(c, "alarm", cfg.toString())
        }
        schedule(c)
    }
}

@UnstableApi
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Alarm.schedule(context)
        RadioWidget.refresh(context)
    }
}
