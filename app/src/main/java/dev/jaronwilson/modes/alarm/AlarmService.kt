package dev.jaronwilson.modes.alarm

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import dev.jaronwilson.modes.AppGraph
import dev.jaronwilson.modes.ModesApp
import dev.jaronwilson.modes.R
import kotlinx.coroutines.launch

/**
 * The wake alarm, ringing.
 *
 * A foreground service rather than an activity, because on recent Android a
 * full-screen intent needs a special access the user may not have granted, so
 * an alarm that depends on the screen taking over can stay silent. A service
 * started from the exact alarm always runs: it plays the alarm sound on a loop,
 * vibrates, and posts a high-priority alarm notification with Dismiss. The
 * full-screen [AlarmActivity] is offered on top of that for when the phone is
 * locked, but the sound and the way to stop it never depend on it.
 */
class AlarmService : Service() {

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISMISS -> { stopEverything(reschedule = true); return START_NOT_STICKY }
            ACTION_SNOOZE -> { snooze(); return START_NOT_STICKY }
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        startRinging()
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val fullScreen = PendingIntent.getActivity(
            this, 1,
            Intent(this, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val dismiss = PendingIntent.getService(
            this, 2,
            Intent(this, AlarmService::class.java).setAction(ACTION_DISMISS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val snooze = PendingIntent.getService(
            this, 3,
            Intent(this, AlarmService::class.java).setAction(ACTION_SNOOZE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, ModesApp.CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat_modes)
            .setContentTitle("Wake up")
            .setContentText("Alarm")
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Dismiss", dismiss).build())
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Snooze 9 min", snooze).build())
            .build()
    }

    private fun startRinging() {
        runCatching {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
        }.onFailure { Log.w(TAG, "no ringtone", it) }

        runCatching {
            vibrator = getSystemService(Vibrator::class.java)?.apply {
                val pattern = longArrayOf(0, 500, 800)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION") vibrate(pattern, 0)
                }
            }
        }
    }

    private fun stopEverything(reschedule: Boolean) {
        runCatching { ringtone?.stop() }
        runCatching { vibrator?.cancel() }
        ringtone = null
        vibrator = null
        if (reschedule) {
            AppGraph.scope.launch { runCatching { WakeAlarm.sync(applicationContext) } }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun snooze() {
        val at = System.currentTimeMillis() + 9 * 60_000L
        runCatching {
            val am = getSystemService(android.app.AlarmManager::class.java)
            val pi = PendingIntent.getBroadcast(
                this, WakeAlarm.REQ,
                Intent(this, WakeAlarmReceiver::class.java).setAction(WakeAlarm.ACTION_RING),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            am.setAlarmClock(android.app.AlarmManager.AlarmClockInfo(at, pi), pi)
        }
        stopEverything(reschedule = false)
    }

    override fun onDestroy() {
        runCatching { ringtone?.stop() }
        runCatching { vibrator?.cancel() }
        super.onDestroy()
    }

    companion object {
        const val ACTION_DISMISS = "dev.jaronwilson.modes.ALARM_DISMISS"
        const val ACTION_SNOOZE = "dev.jaronwilson.modes.ALARM_SNOOZE"
        const val NOTIFICATION_ID = 3202

        fun start(context: Context) {
            val intent = Intent(context, AlarmService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun dismiss(context: Context) {
            context.startService(
                Intent(context, AlarmService::class.java).setAction(ACTION_DISMISS)
            )
        }

        private const val TAG = "AlarmService"
    }
}
