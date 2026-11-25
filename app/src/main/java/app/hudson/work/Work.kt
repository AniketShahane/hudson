package app.hudson.work

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.hudson.data.Graph
import app.hudson.domain.Boards
import app.hudson.domain.LivePlan
import app.hudson.domain.RefreshPlan
import app.hudson.domain.at
import app.hudson.notify.LiveNotifier
import app.hudson.widget.MinuteTicker
import app.hudson.widget.TrainWidget
import java.util.concurrent.TimeUnit

/** Fetches the feed and redraws every widget, then books the next fetch. */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Graph.init(applicationContext)
        val fetched = Graph.feed.refresh(maxAgeMs = 20_000)
        TrainWidget().updateAll(applicationContext)
        MinuteTicker.plan(applicationContext)
        Scheduler.planNext(applicationContext)
        return if (fetched.isSuccess || runAttemptCount >= 2) Result.success() else Result.retry()
    }
}

/** One beat of a Live Update the user started: refresh, repost, book the next beat. */
class LiveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Graph.init(applicationContext)
        val now = System.currentTimeMillis()
        val settings = Graph.settings.current()
        if (!LivePlan.active(settings.liveUntilMs, now)) {
            LiveNotifier.cancel(applicationContext)
            return Result.success()
        }
        Graph.feed.refresh(maxAgeMs = 15_000)
        val feed = Graph.feed.cached()
        val favorite = settings.favorites.at(settings.liveFavorite)
        val card = LivePlan.card(Boards.build(favorite, feed, now), now, settings.liveUntilMs)
        LiveNotifier.post(applicationContext, card)
        TrainWidget().updateAll(applicationContext)
        // A last beat at the end tidies the card away even if the system has not timed it out.
        val next = LivePlan.nextTick(System.currentTimeMillis(), settings.liveUntilMs) ?: settings.liveUntilMs
        Scheduler.liveAt(applicationContext, next)
        return Result.success()
    }
}

object Scheduler {
    private const val PERIODIC = "refresh-periodic"
    private const val NEXT = "refresh-next"
    private const val NOW = "refresh-now"
    private const val LIVE = "live"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** The 15-minute floor (WorkManager's minimum) while any widget exists. */
    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES).setConstraints(network).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun stopAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        listOf(PERIODIC, NEXT, NOW).forEach { wm.cancelUniqueWork(it) }
    }

    /** A fetch as soon as possible, e.g. after a tap on the widget. */
    fun refreshNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(network)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        // KEEP: a second request while one is running must not cancel it and start over.
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    /** Books a fetch just after the first train on any widget leaves. */
    suspend fun planNext(context: Context) {
        val ids = GlanceAppWidgetManager(context).getGlanceIds(TrainWidget::class.java)
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        val settings = Graph.settings.current()
        val feed = Graph.feed.cached()
        val boards = settings.favorites.map { Boards.build(it, feed, now) }
        val delay = RefreshPlan.nextAt(boards, now) - now
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(network)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        // Called last by the worker that may itself be NEXT: replacing it then cancels nothing that matters.
        WorkManager.getInstance(context).enqueueUniqueWork(NEXT, ExistingWorkPolicy.REPLACE, request)
    }

    fun liveAt(context: Context, atMs: Long) {
        val delay = (atMs - System.currentTimeMillis()).coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<LiveWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(LIVE, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelLive(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(LIVE)
    }
}

/** Starting, switching and stopping the commute Live Update. */
object LiveSession {
    suspend fun start(context: Context, favoriteIndex: Int) {
        Graph.init(context)
        val now = System.currentTimeMillis()
        Graph.settings.update { it.copy(liveFavorite = favoriteIndex, liveUntilMs = now + it.liveMinutes * 60_000L) }
        Scheduler.liveAt(context, now)
    }

    suspend fun next(context: Context) {
        Graph.init(context)
        Graph.settings.update { it.copy(liveFavorite = it.liveFavorite + 1) }
        Scheduler.liveAt(context, System.currentTimeMillis())
    }

    suspend fun stop(context: Context) {
        Graph.init(context)
        Graph.settings.update { it.copy(liveUntilMs = 0) }
        Scheduler.cancelLive(context)
        LiveNotifier.cancel(context)
        TrainWidget().updateAll(context)
    }
}
