package app.hudson.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.runComposition
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.size
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.unit.ColorProvider
import app.hudson.data.Graph
import app.hudson.domain.Board
import app.hudson.domain.Boards
import app.hudson.domain.Favorite
import app.hudson.domain.PosterLayout
import app.hudson.domain.Redraw
import app.hudson.ui.MainActivity
import app.hudson.work.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Repaints widgets and returns only once the launcher has the new picture.
 *
 * Glance's own update() only queues the work for a background session and returns. From a tap or
 * an alarm that is too late: the receiver finishes, and a phone that freezes background apps
 * (One UI does, hard, when the app is sitting in recents) can stop the process before the
 * session has drawn anything, so the tap seems to do nothing. Here the picture is composed and
 * handed to the launcher before returning.
 */
object Widgets {
    suspend fun repaintAll(context: Context) {
        val ids = runCatching { GlanceAppWidgetManager(context).getGlanceIds(TrainWidget::class.java) }.getOrDefault(emptyList())
        ids.forEach { repaint(context, it) }
    }

    @OptIn(ExperimentalGlanceApi::class)
    suspend fun repaint(context: Context, id: GlanceId) {
        runCatching {
            val manager = AppWidgetManager.getInstance(context)
            val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
            val state = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
            val views = withTimeout(DIRECT_PAINT_MS) {
                TrainWidget().runComposition(context, id, manager.getAppWidgetOptions(appWidgetId), null, state).first()
            }
            manager.updateAppWidget(appWidgetId, views)
        }
        // Also tell any session Glance still has open, so it cannot later repaint what it knew before.
        runCatching { TrainWidget().update(context, id) }
    }

    private const val DIRECT_PAINT_MS = 4_000L
}

/** Which favorite a widget shows; each widget keeps its own. */
internal val FavoriteIndexKey = intPreferencesKey("favorite_index")

/**
 * PATH departures as a Swiss transit poster. The whole widget is one painted picture
 * ([PosterRenderer]); tapping it steps to the next favorite.
 */
class TrainWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    // Exact: the picture is painted for the size the launcher really gives, one per orientation.
    // Fixed size buckets made the launcher stretch a picture painted for some other shape.
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Graph.init(context)
        val settingsNow = Graph.settings.current()
        Graph.feed.cached()
        MinuteTicker.plan(context)
        provideContent {
            // Collected here rather than read once above: this block lives on for a while after an
            // update, and anything captured outside it would be repainted as it was back then.
            val settings by Graph.settings.flow.collectAsState(settingsNow)
            val feed by Graph.feed.flow.collectAsState()
            val favorites = settings.favorites.ifEmpty { Favorite.defaults }
            val index = currentState<Preferences>()[FavoriteIndexKey] ?: 0
            val pos = Math.floorMod(index, favorites.size)
            val now = System.currentTimeMillis()
            Poster(Boards.build(favorites[pos], feed, now), pos, favorites.size, now, settings.matchPhone)
        }
    }
}

/** [now] is a parameter so that a repaint a minute later is never skipped as "nothing changed". */
@Composable
private fun Poster(board: Board, pos: Int, count: Int, now: Long, matchPhone: Boolean) {
    val context = LocalContext.current
    val size = LocalSize.current
    val w = size.width.value
    val h = size.height.value
    val scale = paintScale(context, w, h)
    val open = actionStartActivity(
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_FAVORITE, pos)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
    )
    val several = count > 1
    val description = PosterRenderer.describe(board, now) + if (several) " Tap for the next station." else ""
    // The colour behind and Crop (never FillBounds) mean a launcher that reports a slightly
    // different size than it draws costs a sliver of margin, not a stretched picture.
    val ground = if (matchPhone) GlanceModifier.background(PosterRenderer.groundRes(board, w, h))
    else GlanceModifier.background(Color(PosterRenderer.ground(board, w, h)))
    Box(
        modifier = GlanceModifier.fillMaxSize()
            .cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .then(ground)
            .clickable(if (several) actionRunCallback<CycleFavoriteAction>() else open),
    ) {
        if (matchPhone) {
            // Uncoloured layers, each tinted by the launcher from a colour resource: the phone's
            // Material You colours stay current without the app having to notice they changed.
            PosterRenderer.renderLayers(context, board, pos, count, w, h, now, scale).forEachIndexed { i, painted ->
                @Suppress("RestrictedApi")
                val tint = painted.layer.tint?.let { ColorFilter.tint(ColorProvider(it)) }
                Image(
                    provider = ImageProvider(painted.bitmap),
                    contentDescription = if (i == 0) description else null,
                    contentScale = ContentScale.Crop,
                    colorFilter = tint,
                    modifier = GlanceModifier.fillMaxSize(),
                )
            }
        } else {
            Image(
                provider = ImageProvider(PosterRenderer.render(context, board, pos, count, w, h, now, scale)),
                contentDescription = description,
                contentScale = ContentScale.Crop,
                modifier = GlanceModifier.fillMaxSize(),
            )
        }
        if (several) {
            // Over the station name and its small arrow: opens the app on this station.
            val zone = PosterLayout.openZone(w, h)
            Box(
                modifier = GlanceModifier.size(zone.width.dp, zone.height.dp)
                    .semantics { contentDescription = "Open Hudson on ${board.favorite.title}" }
                    .clickable(open),
            ) {}
        }
    }
}

/**
 * Pixels per dp to paint at: the screen's own, unless the picture would then take more than a
 * fifth of a screenful. The system caps all of a widget's pictures together (portrait and
 * landscape here) at one and a half screens of pixels.
 */
private fun paintScale(context: Context, widthDp: Float, heightDp: Float): Float {
    val dm = context.resources.displayMetrics
    val budgetPx = dm.widthPixels.toFloat() * dm.heightPixels * 0.2f
    val fits = kotlin.math.sqrt(budgetPx / (widthDp * heightDp).coerceAtLeast(1f))
    return minOf(dm.density, fits).coerceAtLeast(1f)
}

class TrainWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TrainWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        Scheduler.ensurePeriodic(context)
        Scheduler.refreshNow(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        Scheduler.stopAll(context)
        MinuteTicker.cancel(context)
    }
}

/** Steps this widget to the next favorite and redraws at once from the trains already known. */
class CycleFavoriteAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        updateAppWidgetState(context, glanceId) { prefs -> prefs[FavoriteIndexKey] = (prefs[FavoriteIndexKey] ?: 0) + 1 }
        Widgets.repaint(context, glanceId)
        // Only when the trains are getting old: a fetch landing a moment after every tap repaints
        // the card a second time, which reads as the tap misfiring.
        Graph.init(context)
        if (Redraw.shouldFetch(Graph.feed.cached()?.fetchedAtMs, System.currentTimeMillis())) Scheduler.refreshNow(context)
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
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                Graph.init(app)
                if (GlanceAppWidgetManager(app).getGlanceIds(TrainWidget::class.java).isEmpty()) return@launch
                Widgets.repaintAll(app)
                // The fetch is handed to a worker rather than awaited here. The system delivers an
                // app's broadcasts one at a time, and a tap on the widget is one too: waiting on the
                // network in this receiver made taps hang for seconds, just as a train was leaving.
                if (Redraw.shouldFetch(Graph.feed.cached()?.fetchedAtMs, System.currentTimeMillis())) Scheduler.refreshNow(app)
                MinuteTicker.plan(app)
            } finally {
                pending.finish()
            }
        }
    }
}
