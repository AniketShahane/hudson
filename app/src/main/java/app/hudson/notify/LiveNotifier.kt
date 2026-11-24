package app.hudson.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import app.hudson.R
import app.hudson.domain.LiveCard
import app.hudson.ui.MainActivity
import app.hudson.work.LiveSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * The commute Live Update. On Android 16+ it asks to be promoted, which on One UI puts it in
 * the Now Bar on the lock screen; on Android 17 the next three trains are MetricStyle timers,
 * which the system counts down by itself between refreshes.
 */
object LiveNotifier {
    const val CHANNEL = "live"
    private const val ID = 7

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        // Default importance: promoted Live Updates may not sit on an IMPORTANCE_MIN channel.
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.channel_live), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = context.getString(R.string.channel_live_description)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    /** Whether the user lets this app's Live Updates reach the status bar and lock screen. */
    fun canPromote(context: Context): Boolean =
        Build.VERSION.SDK_INT < 36 || context.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()

    fun post(context: Context, card: LiveCard) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        ensureChannel(context)
        val now = System.currentTimeMillis()
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_train)
            .setContentTitle(card.title)
            .setContentText(card.summary)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setDeleteIntent(action(context, LiveActionReceiver.DISMISSED, 1))
            .setTimeoutAfter((card.endsAtMs - now).coerceAtLeast(1_000))
            .addAction(Notification.Action.Builder(null, context.getString(R.string.live_next_favorite), action(context, LiveActionReceiver.NEXT, 2)).build())
            .addAction(Notification.Action.Builder(null, context.getString(R.string.live_stop), action(context, LiveActionReceiver.STOP, 3)).build())

        val first = card.chipWhenMs
        if (first != null && first > now) {
            builder.setWhen(first).setShowWhen(true).setUsesChronometer(true).setChronometerCountdown(true)
        } else {
            builder.setShowWhen(false)
        }

        if (Build.VERSION.SDK_INT >= 36) {
            builder.setRequestPromotedOngoing(true)
            card.chipText?.let { builder.setShortCriticalText(it) }
        }

        if (Build.VERSION.SDK_INT >= 37 && card.metrics.isNotEmpty()) {
            val style = Notification.MetricStyle()
            card.metrics.forEach { m ->
                val value = Notification.Metric.TimeDifference.forTimer(Instant.ofEpochMilli(m.atMs), Notification.Metric.TimeDifference.FORMAT_ADAPTIVE)
                style.addMetric(Notification.Metric(value, m.label))
            }
            builder.setStyle(style)
        } else {
            builder.setStyle(Notification.BigTextStyle().bigText(card.summary))
        }
        nm.notify(ID, builder.build())
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }

    private fun action(context: Context, what: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        context, code, Intent(context, LiveActionReceiver::class.java).setAction(what),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Buttons on the Live Update, and its dismissal (which ends the session: never repost a dismissed card). */
class LiveActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                when (intent.action) {
                    NEXT -> LiveSession.next(context)
                    STOP, DISMISSED -> LiveSession.stop(context)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val NEXT = "app.hudson.live.NEXT"
        const val STOP = "app.hudson.live.STOP"
        const val DISMISSED = "app.hudson.live.DISMISSED"
    }
}
