package app.hudson.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import app.hudson.data.Graph
import app.hudson.domain.Board
import app.hudson.domain.Boards
import app.hudson.domain.Favorite
import app.hudson.domain.Redraw
import app.hudson.ui.MainActivity
import app.hudson.work.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
        val settings = Graph.settings.current()
        val feed = Graph.feed.cached()
        val favorites = settings.favorites.ifEmpty { Favorite.defaults }
        val now = System.currentTimeMillis()
        MinuteTicker.plan(context, favorites.map { Boards.build(it, feed, now) }, now)
        provideContent {
            val index = currentState<Preferences>()[FavoriteIndexKey] ?: 0
            val pos = Math.floorMod(index, favorites.size)
            val board = Boards.build(favorites[pos], feed, System.currentTimeMillis())
            Poster(board, pos, favorites.size)
        }
    }
}

@Composable
private fun Poster(board: Board, pos: Int, count: Int) {
    val context = LocalContext.current
    val size = LocalSize.current
    val now = System.currentTimeMillis()
    val w = size.width.value
    val h = size.height.value
    val bitmap = PosterRenderer.render(context, board, pos, count, w, h, now, paintScale(context, w, h))
    val tap = if (count > 1) actionRunCallback<CycleFavoriteAction>() else actionStartActivity<MainActivity>()
    // The colour behind and Crop (never FillBounds) mean a launcher that reports a slightly
    // different size than it draws costs a sliver of margin, not a stretched picture.
    Box(
        modifier = GlanceModifier.fillMaxSize()
            .cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .background(Color(PosterRenderer.ground(board, w, h)))
            .clickable(tap),
    ) {
        Image(
            provider = ImageProvider(bitmap),
            contentDescription = PosterRenderer.describe(board, now) + if (count > 1) " Tap for the next station." else "",
            contentScale = ContentScale.Crop,
            modifier = GlanceModifier.fillMaxSize(),
        )
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

/** Steps this widget to the next favorite, redraws at once from the cached feed, then refreshes. */
class CycleFavoriteAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        updateAppWidgetState(context, glanceId) { prefs -> prefs[FavoriteIndexKey] = (prefs[FavoriteIndexKey] ?: 0) + 1 }
        TrainWidget().update(context, glanceId)
        Scheduler.refreshNow(context)
    }
}

/**
 * Repaints the widgets when the big minutes number changes. A non-waking alarm: it never wakes
 * a sleeping phone, and fires as soon as the screen comes on, so the number is right when seen.
 */
object MinuteTicker {
    private const val WINDOW_MS = 4_000L

    fun plan(context: Context, boards: List<Board>, nowMs: Long) {
        val at = Redraw.nextAt(boards, nowMs)
        if (at == null) {
            cancel(context)
            return
        }
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
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                TrainWidget().updateAll(context)
            } finally {
                pending.finish()
            }
        }
    }
}
