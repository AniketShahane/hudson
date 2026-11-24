package app.hudson.widget

import android.content.Context
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.hudson.R
import app.hudson.data.Graph
import app.hudson.domain.Board
import app.hudson.domain.BoardState
import app.hudson.domain.Boards
import app.hudson.domain.Departure
import app.hudson.domain.TimeText
import app.hudson.ui.MainActivity
import app.hudson.work.LiveSession
import app.hudson.work.Scheduler

/** Which favorite a widget shows; each widget keeps its own, so two widgets can show two boards. */
internal val FavoriteIndexKey = intPreferencesKey("favorite_index")

/**
 * PATH departures as a Material 3 Expressive widget.
 *
 * The next train is a big countdown drawn by a system Chronometer, so it ticks every second
 * without the app waking up; later trains are clock times, which cannot go stale. Tap the
 * swap button (or anywhere on the smallest sizes) to step through your favorites.
 */
class TrainWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(STRIP, SQUARE, WIDE, CARD, TALL))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Graph.init(context)
        val settings = Graph.settings.current()
        val feed = Graph.feed.cached()
        val liveOn = settings.liveUntilMs > System.currentTimeMillis()
        val canNotify = context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()
        provideContent {
            val index = currentState<Preferences>()[FavoriteIndexKey] ?: 0
            val favorites = settings.favorites.ifEmpty { app.hudson.domain.Favorite.defaults }
            val pos = Math.floorMod(index, favorites.size)
            val board = Boards.build(favorites[pos], feed, System.currentTimeMillis())
            GlanceTheme {
                Content(board, pos, favorites.size, liveOn, canNotify)
            }
        }
    }

    companion object {
        // Bounds in dp; the launcher shows the largest that fits. Lock-screen hosts are usually 2x2 or 4x2.
        internal val STRIP = DpSize(110.dp, 48.dp)
        internal val SQUARE = DpSize(110.dp, 110.dp)
        internal val WIDE = DpSize(220.dp, 48.dp)
        internal val CARD = DpSize(220.dp, 110.dp)
        internal val TALL = DpSize(220.dp, 200.dp)
    }
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

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Scheduler.refreshNow(context)
    }
}

/** Starts (or stops) the commute Live Update for the favorite this widget is showing. */
class LiveAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Graph.init(context)
        val settings = Graph.settings.current()
        if (settings.liveUntilMs > System.currentTimeMillis()) {
            LiveSession.stop(context)
            return
        }
        val index = getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)[FavoriteIndexKey] ?: 0
        LiveSession.start(context, Math.floorMod(index, settings.favorites.size.coerceAtLeast(1)))
        TrainWidget().update(context, glanceId)
    }
}

private fun askIntent(context: Context) = android.content.Intent(context, MainActivity::class.java)
    .putExtra(MainActivity.EXTRA_ASK_NOTIFICATIONS, true)

// ---------------------------------------------------------------------------------------------
// Layout

@Composable
private fun Content(board: Board, pos: Int, count: Int, liveOn: Boolean, canNotify: Boolean) {
    val size = LocalSize.current
    val twoRows = size.height >= TrainWidget.SQUARE.height
    val wide = size.width >= TrainWidget.WIDE.width
    val tall = size.height >= TrainWidget.TALL.height
    val openApp = actionStartActivity<MainActivity>()
    val cycle = actionRunCallback<CycleFavoriteAction>()

    val shell = GlanceModifier
        .fillMaxSize()
        .background(GlanceTheme.colors.widgetBackground)
        .cornerRadius(28.dp)

    when {
        // One row: whole widget steps through favorites.
        !twoRows -> Row(
            shell.padding(horizontal = 14.dp).clickable(cycle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LineDots(board.next?.colors.orEmpty(), 8)
            Spacer(GlanceModifier.width(8.dp))
            Column(GlanceModifier.defaultWeight()) {
                Label(if (wide) board.favorite.title else board.favorite.compact, if (wide) 14 else 12, GlanceTheme.colors.onSurface, FontWeight.Bold)
                if (wide) Label(board.favorite.subtitle, 12, GlanceTheme.colors.onSurfaceVariant)
            }
            Countdown(board, R.layout.widget_countdown_small, GlanceTheme.colors.primary)
        }

        // Small square (typical lock-screen size): the countdown is the whole point.
        !wide -> Column(shell.padding(12.dp).clickable(cycle)) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Label(board.favorite.title, 13, GlanceTheme.colors.onSurface, FontWeight.Bold, GlanceModifier.defaultWeight())
                PageDots(pos, count)
            }
            Label(board.favorite.subtitle, 11, GlanceTheme.colors.onSurfaceVariant)
            Spacer(GlanceModifier.defaultWeight())
            Hero(board, compact = true, modifier = GlanceModifier.fillMaxWidth())
        }

        else -> Column(shell.padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 12.dp)) {
            Header(board, pos, count, cycle, openApp)
            Spacer(GlanceModifier.height(8.dp))
            if (tall) {
                Hero(board, compact = false, modifier = GlanceModifier.fillMaxWidth().clickable(openApp))
                Spacer(GlanceModifier.height(8.dp))
                LaterRows(board, modifier = GlanceModifier.fillMaxWidth().defaultWeight())
                Footer(board, liveOn, canNotify)
            } else {
                Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                    Hero(board, compact = false, modifier = GlanceModifier.defaultWeight().fillMaxHeight().clickable(openApp))
                    Spacer(GlanceModifier.width(8.dp))
                    LaterChips(board, GlanceModifier.width(76.dp).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun Header(board: Board, pos: Int, count: Int, cycle: Action, openApp: Action) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(GlanceModifier.defaultWeight().clickable(cycle)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(board.favorite.title, 16, GlanceTheme.colors.onSurface, FontWeight.Bold)
                Spacer(GlanceModifier.width(6.dp))
                PageDots(pos, count)
            }
            Label(board.favorite.subtitle, 12, GlanceTheme.colors.onSurfaceVariant)
        }
        if (count > 1) {
            IconButton(R.drawable.ic_swap, "Next favorite", cycle, GlanceTheme.colors.secondaryContainer, GlanceTheme.colors.onSecondaryContainer)
        }
    }
}

/** The next train: label, a big ticking countdown, and its clock time. */
@Composable
private fun Hero(board: Board, compact: Boolean, modifier: GlanceModifier) {
    val next = board.next
    Column(
        modifier
            .background(GlanceTheme.colors.primaryContainer)
            .cornerRadius(if (compact) 18.dp else 24.dp)
            .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 6.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (next == null) {
            Label(emptyTitle(board.state), if (compact) 13 else 16, GlanceTheme.colors.onPrimaryContainer, FontWeight.Bold)
            Label(emptyHint(board.state), 11, GlanceTheme.colors.onPrimaryContainer)
            return@Column
        }
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LineDots(next.colors, if (compact) 7 else 9)
            Spacer(GlanceModifier.width(6.dp))
            Label(next.label, if (compact) 11 else 13, GlanceTheme.colors.onPrimaryContainer, FontWeight.Medium, GlanceModifier.defaultWeight())
            if (!compact) Label(TimeText.clock(next.atMs), 13, GlanceTheme.colors.onPrimaryContainer, FontWeight.Medium)
        }
        Countdown(board, if (compact) R.layout.widget_countdown_medium else R.layout.widget_countdown_large, null)
        if (compact) Label(TimeText.clock(next.atMs), 11, GlanceTheme.colors.onPrimaryContainer)
    }
}

/**
 * The countdown, as a platform Chronometer so it ticks on its own. Its colour lives in the
 * layout (light and night variants pick the matching dynamic-colour tone).
 */
@Composable
private fun Countdown(board: Board, layout: Int, fallbackColor: ColorProvider?) {
    val next = board.next
    if (next == null) {
        Label("—", 18, fallbackColor ?: GlanceTheme.colors.onPrimaryContainer, FontWeight.Bold)
        return
    }
    val context = LocalContext.current
    val views = RemoteViews(context.packageName, layout).apply {
        val base = SystemClock.elapsedRealtime() + (next.atMs - System.currentTimeMillis())
        setChronometer(R.id.countdown, base, null, true)
        setChronometerCountDown(R.id.countdown, true)
        setContentDescription(R.id.countdown, "Next train at ${TimeText.clock(next.atMs)}")
    }
    AndroidRemoteViews(views)
}

/** Later trains on the 4x2 card: two stacked chips with clock times. */
@Composable
private fun LaterChips(board: Board, modifier: GlanceModifier) {
    val later = board.later.take(2)
    Column(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (later.isEmpty()) {
            Spacer(GlanceModifier.defaultWeight())
            return@Column
        }
        later.forEachIndexed { i, d ->
            if (i > 0) Spacer(GlanceModifier.height(6.dp))
            Box(
                GlanceModifier.fillMaxWidth().defaultWeight()
                    .background(GlanceTheme.colors.surfaceVariant)
                    .cornerRadius(16.dp)
                    .semantics { contentDescription = "${d.label} at ${TimeText.clock(d.atMs)}" },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Label(TimeText.clock(d.atMs), 15, GlanceTheme.colors.onSurface, FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LineDots(d.colors, 6)
                        Spacer(GlanceModifier.width(4.dp))
                        Label(d.label, 10, GlanceTheme.colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Later trains on the tall card: one row each. */
@Composable
private fun LaterRows(board: Board, modifier: GlanceModifier) {
    Column(modifier) {
        board.later.take(3).forEach { d -> Row1(d) }
    }
}

@Composable
private fun Row1(d: Departure) {
    Row(GlanceModifier.fillMaxWidth().padding(vertical = 5.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        LineDots(d.colors, 8)
        Spacer(GlanceModifier.width(10.dp))
        Label(d.headsign, 14, GlanceTheme.colors.onSurface, FontWeight.Medium, GlanceModifier.defaultWeight())
        Label(TimeText.clock(d.atMs), 14, GlanceTheme.colors.onSurface, FontWeight.Bold)
    }
}

@Composable
private fun Footer(board: Board, liveOn: Boolean, canNotify: Boolean) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val stale = board.state == BoardState.STALE
        Label(TimeText.updated(board.fetchedAtMs), 11, if (stale) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant, modifier = GlanceModifier.defaultWeight())
        IconButton(R.drawable.ic_refresh, "Refresh", actionRunCallback<RefreshAction>(), GlanceTheme.colors.surfaceVariant, GlanceTheme.colors.onSurfaceVariant)
        Spacer(GlanceModifier.width(6.dp))
        IconButton(
            R.drawable.ic_live, if (liveOn) "Stop Live Update" else "Start Live Update",
            // Without notification permission, open the app to ask for it (a widget cannot).
            if (canNotify || liveOn) actionRunCallback<LiveAction>() else actionStartActivity(askIntent(LocalContext.current)),
            if (liveOn) GlanceTheme.colors.tertiary else GlanceTheme.colors.tertiaryContainer,
            if (liveOn) GlanceTheme.colors.onTertiary else GlanceTheme.colors.onTertiaryContainer,
        )
    }
}

@Composable
private fun IconButton(icon: Int, description: String, onClick: Action, container: ColorProvider, content: ColorProvider) {
    Box(
        GlanceModifier.size(36.dp).background(container).cornerRadius(18.dp).clickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(ImageProvider(icon), description, GlanceModifier.size(20.dp), colorFilter = ColorFilter.tint(content))
    }
}

/** A dot per line the train runs as: red, blue, green, yellow, or two for a combined route. */
@Composable
private fun LineDots(colors: List<Long>, sizeDp: Int) {
    if (colors.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        colors.take(2).forEachIndexed { i, c ->
            if (i > 0) Spacer(GlanceModifier.width(2.dp))
            Box(GlanceModifier.size(sizeDp.dp).background(ColorProvider(Color(c.toInt()))).cornerRadius((sizeDp / 2f).dp)) {}
        }
    }
}

/** Where this favorite sits in the list, so it is clear there are more to tap through. */
@Composable
private fun PageDots(pos: Int, count: Int) {
    if (count < 2) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until count.coerceAtMost(6)) {
            if (i > 0) Spacer(GlanceModifier.width(3.dp))
            val on = i == pos
            Box(
                GlanceModifier.size(width = if (on) 12.dp else 5.dp, height = 5.dp)
                    .background(if (on) GlanceTheme.colors.primary else GlanceTheme.colors.outline)
                    .cornerRadius(3.dp),
            ) {}
        }
    }
}

@Composable
private fun Label(text: String, size: Int, color: ColorProvider, weight: FontWeight = FontWeight.Normal, modifier: GlanceModifier = GlanceModifier) {
    Text(text, modifier = modifier, maxLines = 1, style = TextStyle(color = color, fontSize = size.sp, fontWeight = weight))
}

private fun emptyTitle(state: BoardState) = when (state) {
    BoardState.NO_DATA -> "Loading trains"
    BoardState.STALE -> "No recent data"
    else -> "No trains listed"
}

private fun emptyHint(state: BoardState) = when (state) {
    BoardState.NO_DATA -> "Waiting for the PATH feed"
    BoardState.STALE -> "Tap refresh when you have signal"
    else -> "Service may be suspended"
}
