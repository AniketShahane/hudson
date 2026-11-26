package app.hudson.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import app.hudson.R
import app.hudson.domain.LiveCard
import app.hudson.ui.MainActivity
import app.hudson.work.LiveSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The commute Live Update. On Android 16+ it asks to be promoted, which on One UI puts it in
 * the Now Bar on the lock screen, and is drawn as a progress card: a train badge riding a track
 * of ten one-minute dashes in its line colour toward the station. The header counts down by itself between refreshes; the text
 * is clock times only, so nothing on the card goes stale.
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
    fun canPromote(context: Context): Boolean = !promotionApis() ||
        runCatching { context.getSystemService(NotificationManager::class.java).canPostPromotedNotifications() }.getOrDefault(true)

    /**
     * The promotion APIs arrived in a minor release of Android 16 (36.1), so plain API 36 may lack
     * them. They are certain on Android 17, which is what the phone runs; 36.x is tried and tolerated.
     */
    private fun promotionApis(): Boolean = Build.VERSION.SDK_INT >= 36

    /** What became of an attempt to show the card, so the app can say so instead of failing silently. */
    enum class Result {
        /** Showing, and allowed on the lock screen and status bar. */
        SHOWN,
        /** Showing in the notification shade only: the user has not allowed Live Updates for this app. */
        NOT_PROMOTED,
        /** Notifications, or this channel, are turned off for the app. */
        BLOCKED,
        /** The system refused the notification. */
        FAILED,
    }

    fun post(context: Context, card: LiveCard): Result {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return Result.BLOCKED
        ensureChannel(context)
        if (nm.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return Result.BLOCKED
        // The promoted, metric-style card uses APIs that are new and strict about what they accept.
        // If the system rejects it, a plain ongoing card is far better than none at all.
        val shown = runCatching { nm.notify(ID, build(context, card, rich = true)) }.isSuccess ||
            runCatching { nm.notify(ID, build(context, card, rich = false)) }.isSuccess
        return when {
            !shown -> Result.FAILED
            canPromote(context) -> Result.SHOWN
            else -> Result.NOT_PROMOTED
        }
    }

    private fun build(context: Context, card: LiveCard, rich: Boolean): Notification {
        val now = System.currentTimeMillis()
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_train)
            .setContentTitle(card.headline)
            .setContentText(card.detail)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setDeleteIntent(action(context, LiveActionReceiver.DISMISSED, 1))
            .setTimeoutAfter((card.endsAtMs - now).coerceAtLeast(1_000))
            .addAction(Notification.Action.Builder(null, context.getString(R.string.live_next_favorite), action(context, LiveActionReceiver.NEXT, 2)).build())
            .addAction(Notification.Action.Builder(null, context.getString(R.string.live_stop), action(context, LiveActionReceiver.STOP, 3)).build())

        val first = card.chipWhenMs
        if (first != null && first > now) {
            builder.setWhen(first).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        } else {
            builder.setShowWhen(false)
        }

        if (rich && promotionApis()) {
            try {
                builder.setRequestPromotedOngoing(true)
                card.chipText?.let { builder.setShortCriticalText(it) }
            } catch (_: NoSuchMethodError) {
                // Android 16 before QPR: an ordinary ongoing notification.
            }
        }

        val approach = card.approach
        val lineColor = approach?.colors?.firstOrNull()?.toInt()
        if (lineColor != null) builder.setColor(lineColor)
        if (rich) LiveTile.draw(context, card)?.let { builder.setLargeIcon(it) }

        if (rich && Build.VERSION.SDK_INT >= 36 && approach != null) {
            // A track of ten dashes, one a minute, in the train's line colour (alternating for a
            // train on two lines); the system dims the dashes still ahead, so what is left can be
            // counted. The train is a solid badge, so the track never shows through or around it.
            val colors = approach.colors.take(2).ifEmpty { listOf(0xFF8A8A8AL) }
            val style = Notification.ProgressStyle()
                .setStyledByProgress(true)
                .setProgressTrackerIcon(Icon.createWithBitmap(LiveTile.train(context, colors)))
                .setProgressEndIcon(Icon.createWithBitmap(LiveTile.station(context, colors.last())))
            val dashes = LiveTile.DASHES
            val each = approach.max / dashes
            repeat(dashes) { i ->
                val length = if (i == dashes - 1) approach.max - each * i else each
                style.addProgressSegment(Notification.ProgressStyle.Segment(length).setColor(colors[i % colors.size].toInt()))
            }
            style.setProgress(approach.progress.coerceIn(0, approach.max))
            builder.setStyle(style)
        } else {
            builder.setStyle(Notification.BigTextStyle().bigText(card.detail))
        }
        return builder.build()
    }

    /** The system page with the "Live Updates" switch for this app; the plain notification page where that does not exist. */
    fun openPromotionSettings(context: Context) {
        val specific = Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(specific) }.isFailure) openNotificationSettings(context)
    }

    fun openNotificationSettings(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
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
