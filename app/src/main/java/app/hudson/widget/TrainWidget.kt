package app.hudson.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import app.hudson.R
import app.hudson.data.Graph
import app.hudson.data.Settings
import app.hudson.data.feed.FeedSnapshot
import app.hudson.domain.Boards
import app.hudson.domain.Favorite
import app.hudson.domain.PosterLayout
import app.hudson.domain.Redraw
import app.hudson.ui.MainActivity
import app.hudson.work.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * PATH departures as a Swiss transit poster, painted by [PosterRenderer] and handed to the
 * launcher as plain RemoteViews.
 *
 * Built for the tap. The launcher holds two pages: the station showing and the next one, already
 * drawn. A tap only turns the page ([tap]), with a crossfade the launcher runs by itself; nothing
 * is loaded or drawn before the change is on screen. Afterwards, off the critical path, the
 * widget is tidied ([paint]): the station showing is moved back to page 0 and the one after it is
 * drawn on page 1, ready for the next tap.
 *
 * At rest the launcher always shows page 0, which is also what it shows if it ever rebuilds the
 * widget from scratch. Page 1 is only on screen between a tap and the tidy-up that follows it.
 */
object Widgets {
    private const val PREFS = "widgets"

    /** How long the launcher's crossfade runs (res/anim/widget_in, widget_out), with a little to spare. */
    private const val TURN_MS = 230L

    private val PAGES = intArrayOf(R.id.page0, R.id.page1)
    private val LAYERS = arrayOf(
        intArrayOf(R.id.p0_l0, R.id.p0_l1, R.id.p0_l2, R.id.p0_l3, R.id.p0_l4),
        intArrayOf(R.id.p1_l0, R.id.p1_l1, R.id.p1_l2, R.id.p1_l3, R.id.p1_l4),
    )

    /** One painter at a time. */
    private val painting = Mutex()

    /** Guards what is sent to the launcher together with the record of it, against a tap arriving meanwhile. */
    private val lock = Any()

    /** Counts taps. A paint that started before the latest tap must not overwrite what that tap showed. */
    private val taps = AtomicInteger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tidy: Job? = null

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun indexKey(id: Int) = "index_$id"

    /** Which page the launcher is showing: 0 at rest, 1 just after a tap. */
    private fun showingKey(id: Int) = "showing_$id"

    /** Whether the hidden page holds the next station, so a tap can simply turn to it. */
    private fun readyKey(id: Int) = "ready_$id"

    fun ids(context: Context): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, TrainWidgetReceiver::class.java))

    /**
     * The tap's first act: step to the next favorite and, if the launcher already holds its
     * picture, turn to it. One small file read and one call to the launcher; no settings, no
     * trains, no drawing. Returns whether the page was turned; if not, [show] draws it.
     */
    fun tap(context: Context, id: Int): Boolean = synchronized(lock) {
        taps.incrementAndGet()
        tidy?.cancel()
        val prefs = prefs(context)
        val ready = prefs.getBoolean(readyKey(id), false)
        val edit = prefs.edit().putInt(indexKey(id), prefs.getInt(indexKey(id), 0) + 1).putBoolean(readyKey(id), false)
        if (ready) {
            val to = 1 - prefs.getInt(showingKey(id), 0)
            val turn = RemoteViews(context.packageName, R.layout.widget_poster).apply { setDisplayedChild(R.id.flipper, to) }
            AppWidgetManager.getInstance(context).partiallyUpdateAppWidget(id, turn)
            edit.putInt(showingKey(id), to)
        }
        edit.apply()
        ready
    }

    /** A tap that found no picture waiting: draw the station on the hidden page and turn to it. */
    suspend fun show(context: Context, id: Int) = painting.withLock {
        val stamp = taps.get()
        Graph.init(context)
        val settings = Graph.settings.current()
        val feed = Graph.feed.cached()
        val manager = AppWidgetManager.getInstance(context)
        val prefs = prefs(context)
        val hidden = 1 - prefs.getInt(showingKey(id), 0)
        val views = views(context, manager, id) { size, n -> build(context, id, size, settings, feed, prefs.getInt(indexKey(id), 0), n, current = hidden, withNext = false, display = hidden) }
        commit(stamp) {
            manager.updateAppWidget(id, views)
            prefs.edit().putInt(showingKey(id), hidden).apply()
        }
    }

    /** Tidies up after a tap once its crossfade has run, without holding up the next tap. */
    fun tidyLater(context: Context) {
        synchronized(lock) {
            tidy?.cancel()
            tidy = scope.launch {
                delay(TURN_MS)
                runCatching { repaintAll(context) }
            }
        }
    }

    fun forget(context: Context, ids: IntArray) {
        val edit = prefs(context).edit()
        ids.forEach { edit.remove(indexKey(it)).remove(readyKey(it)).remove(showingKey(it)) }
        edit.apply()
    }

    /** Paints every widget and returns only once the launcher has the pictures. */
    suspend fun repaintAll(context: Context) {
        val all = ids(context)
        if (all.isEmpty()) return
        paint(context, all)
    }

    suspend fun repaint(context: Context, id: Int) = paint(context, intArrayOf(id))

    private suspend fun paint(context: Context, ids: IntArray) = painting.withLock {
        val stamp = taps.get()
        Graph.init(context)
        val settings = Graph.settings.current()
        val feed = Graph.feed.cached()
        val manager = AppWidgetManager.getInstance(context)
        val prefs = prefs(context)
        for (id in ids) {
            val index = prefs.getInt(indexKey(id), 0)
            if (prefs.getInt(showingKey(id), 0) == 1) {
                // After a tap: bring the station showing back to page 0. Both pages hold the same
                // picture for this turn, so nothing is seen to change. Page 1 is left alone until
                // the turn is over, or its new picture would show through for a moment.
                val settle = views(context, manager, id) { size, n -> build(context, id, size, settings, feed, index, n, current = 0, withNext = false, display = 0) }
                val done = commit(stamp) {
                    manager.updateAppWidget(id, settle)
                    prefs.edit().putInt(showingKey(id), 0).apply()
                }
                if (!done) return@withLock
                delay(TURN_MS)
            }
            // At rest: the station on page 0, the next one waiting on page 1. No page turn is sent,
            // so nothing animates, and a launcher rebuilding the widget shows page 0.
            val rest = views(context, manager, id) { size, n -> build(context, id, size, settings, feed, index, n, current = 0, withNext = true, display = null) }
            val done = commit(stamp) {
                manager.updateAppWidget(id, rest)
                prefs.edit().putBoolean(readyKey(id), settings.favorites.size > 1).apply()
            }
            if (!done) return@withLock
        }
        MinuteTicker.plan(context)
    }

    /** Sends to the launcher and records it as one step, unless a tap has arrived since [stamp] was read. */
    private inline fun commit(stamp: Int, block: () -> Unit): Boolean = synchronized(lock) {
        if (taps.get() != stamp) return false
        block()
        true
    }

    private inline fun views(context: Context, manager: AppWidgetManager, id: Int, one: (SizeF, Int) -> RemoteViews): RemoteViews {
        val sizes = sizes(manager.getAppWidgetOptions(id))
        val bySize = sizes.associateWith { one(it, sizes.size) }
        return if (bySize.size == 1) bySize.values.first() else RemoteViews(bySize)
    }

    /** The sizes the launcher may show this widget at: normally one upright and one sideways. */
    private fun sizes(options: Bundle): List<SizeF> {
        @Suppress("DEPRECATION")
        val listed = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
        if (!listed.isNullOrEmpty()) return listed.distinct().take(8)
        val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
        val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        val maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        if (minW <= 0 || minH <= 0) return listOf(SizeF(220f, 110f))
        return listOf(SizeF(minW.toFloat(), maxOf(maxH, minH).toFloat()), SizeF(maxOf(maxW, minW).toFloat(), minH.toFloat())).distinct()
    }

    /**
     * One size of one widget. The station showing is painted on page [current]; with [withNext]
     * the station after it is painted on the other page; [display] turns the launcher to a page
     * (null leaves it where it is, so nothing animates).
     */
    internal fun build(
        context: Context, id: Int, size: SizeF, settings: Settings, feed: FeedSnapshot?, index: Int, sizeCount: Int = 1,
        current: Int = 0, withNext: Boolean = true, display: Int? = null,
    ): RemoteViews {
        val favorites = settings.favorites.ifEmpty { Favorite.defaults }
        val count = favorites.size
        val pos = Math.floorMod(index, count)
        val several = count > 1
        val now = System.currentTimeMillis()
        val w = size.width
        val h = size.height
        val scale = paintScale(context, w, h, pictures = (if (several) 2 else 1) * sizeCount)
        val views = RemoteViews(context.packageName, R.layout.widget_poster)

        val board = Boards.build(favorites[pos], feed, now)
        fillPage(context, views, current, board, pos, count, w, h, now, scale, settings.matchPhone)
        if (several && withNext) {
            val nextPos = Math.floorMod(pos + 1, count)
            fillPage(context, views, 1 - current, Boards.build(favorites[nextPos], feed, now), nextPos, count, w, h, now, scale, settings.matchPhone)
        }
        if (display != null) views.setDisplayedChild(R.id.flipper, display)

        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java)
                .setData(Uri.parse("hudson://widget/$id"))
                .putExtra(MainActivity.EXTRA_FAVORITE, pos)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setContentDescription(R.id.tap, PosterRenderer.describe(board, now) + if (several) " Tap for the next station." else "")
        if (several) {
            views.setOnClickPendingIntent(R.id.tap, WidgetTapReceiver.cycle(context, id))
            val zone = PosterLayout.openZone(w, h)
            views.setViewVisibility(R.id.open, View.VISIBLE)
            views.setViewLayoutWidth(R.id.open, zone.width, TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutHeight(R.id.open, zone.height, TypedValue.COMPLEX_UNIT_DIP)
            views.setContentDescription(R.id.open, "Open Hudson on ${board.favorite.title}")
            views.setOnClickPendingIntent(R.id.open, open)
        } else {
            views.setOnClickPendingIntent(R.id.tap, open)
            views.setViewVisibility(R.id.open, View.GONE)
        }
        return views
    }

    private fun fillPage(context: Context, views: RemoteViews, page: Int, board: app.hudson.domain.Board, pos: Int, count: Int, w: Float, h: Float, now: Long, scale: Float, matchPhone: Boolean) {
        val slots = LAYERS[page]
        if (!matchPhone) {
            // The line-colour poster: one full picture.
            views.setInt(PAGES[page], "setBackgroundColor", PosterRenderer.ground(board, w, h))
            views.setImageViewBitmap(slots[0], PosterRenderer.render(context, board, pos, count, w, h, now, scale))
            views.setColorStateList(slots[0], "setImageTintList", null as ColorStateList?)
            views.setViewVisibility(slots[0], View.VISIBLE)
            for (i in 1 until slots.size) views.setViewVisibility(slots[i], View.GONE)
            return
        }
        // The phone's colours: uncoloured layers, each tinted by the launcher from a colour
        // resource, so they stay the phone's current Material You colours with no repaint.
        views.setColor(PAGES[page], "setBackgroundColor", PosterRenderer.groundRes(board, w, h))
        val layers = PosterRenderer.renderLayers(context, board, pos, count, w, h, now, scale)
        slots.forEachIndexed { i, slot ->
            val painted = layers.getOrNull(i)
            if (painted == null) {
                views.setViewVisibility(slot, View.GONE)
            } else {
                views.setViewVisibility(slot, View.VISIBLE)
                views.setImageViewBitmap(slot, painted.bitmap)
                val tint = painted.layer.tint
                if (tint != null) views.setColorStateList(slot, "setImageTintList", tint)
                else views.setColorStateList(slot, "setImageTintList", null as ColorStateList?)
            }
        }
    }

    /**
     * Pixels per dp to paint at: the screen's own, unless the widget's pictures together would
     * take more than three quarters of a screenful. The system caps all of a widget's pictures
     * (two pages, upright and sideways) at one and a half screens of pixels.
     */
    private fun paintScale(context: Context, widthDp: Float, heightDp: Float, pictures: Int): Float {
        val dm = context.resources.displayMetrics
        val budgetPx = dm.widthPixels.toFloat() * dm.heightPixels * 0.75f / pictures.coerceAtLeast(1)
        val fits = sqrt(budgetPx / (widthDp * heightDp).coerceAtLeast(1f))
        return minOf(dm.density, fits).coerceAtLeast(1f)
    }
}

/** Runs [block] to its end before the broadcast is declared handled, so the process is not frozen mid-paint. */
private fun BroadcastReceiver.finishAfter(context: Context, block: suspend (Context) -> Unit) {
    val pending = goAsync()
    val app = context.applicationContext
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        try {
            block(app)
        } finally {
            pending.finish()
        }
    }
}

class TrainWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = finishAfter(context) { Widgets.repaintAll(it) }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        finishAfter(context) { Widgets.repaint(it, id) }

    override fun onEnabled(context: Context) {
        Scheduler.ensurePeriodic(context)
        Scheduler.refreshNow(context)
    }

    override fun onDeleted(context: Context, ids: IntArray) = Widgets.forget(context, ids)

    override fun onDisabled(context: Context) {
        Scheduler.stopAll(context)
        MinuteTicker.cancel(context)
    }
}

/**
 * A tap on the widget. The page is turned first, on the spot; only then is anything loaded. The
 * tidy-up that follows is not awaited, so a second tap is never kept waiting for it. Sent as a
 * foreground broadcast so the system delivers it without queueing.
 */
class WidgetTapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val turned = Widgets.tap(context.applicationContext, id)
        finishAfter(context) { app ->
            if (!turned) Widgets.show(app, id)
            Widgets.tidyLater(app)
            // Only when the trains are getting old: a fetch landing a moment after every tap
            // repaints the card a second time, which reads as the tap misfiring.
            if (Redraw.shouldFetch(Graph.feed.cached()?.fetchedAtMs, System.currentTimeMillis())) Scheduler.refreshNow(app)
        }
    }

    companion object {
        private const val EXTRA_ID = "widget_id"

        fun cycle(context: Context, id: Int): PendingIntent = PendingIntent.getBroadcast(
            context, id,
            Intent(context, WidgetTapReceiver::class.java).putExtra(EXTRA_ID, id).addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/**
 * Repaints the widgets when what they show changes ([Redraw]), and asks for a fetch when the trains
 * are getting old. A non-waking alarm: it never wakes a sleeping phone, and fires as soon as the
 * phone is awake again, so the widget is brought up to date as the screen comes on rather than
 * whenever background work next gets a turn.
 */
object MinuteTicker {
    private const val WINDOW_MS = 4_000L

    suspend fun plan(context: Context) {
        val now = System.currentTimeMillis()
        val favorites = Graph.settings.current().favorites.ifEmpty { Favorite.defaults }
        val feed = Graph.feed.cached()
        val at = Redraw.nextAt(favorites.map { Boards.build(it, feed, now) }, now)
        context.getSystemService(AlarmManager::class.java).setWindow(AlarmManager.RTC, at, WINDOW_MS, pending(context))
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context))
    }

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 11, Intent(context, MinuteTickReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

class MinuteTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = finishAfter(context) { app ->
        Graph.init(app)
        if (Widgets.ids(app).isEmpty()) return@finishAfter
        Widgets.repaintAll(app)
        // The fetch is handed to a worker rather than awaited here: the system delivers an app's
        // broadcasts one at a time, and a tap on the widget is one too.
        if (Redraw.shouldFetch(Graph.feed.cached()?.fetchedAtMs, System.currentTimeMillis())) Scheduler.refreshNow(app)
    }
}
