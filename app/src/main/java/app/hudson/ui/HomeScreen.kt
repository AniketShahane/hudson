package app.hudson.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.hudson.R
import app.hudson.core.Direction
import app.hudson.core.Network
import app.hudson.core.Side
import app.hudson.core.Station
import app.hudson.data.Graph
import app.hudson.data.Settings
import app.hudson.data.feed.FeedSnapshot
import app.hudson.domain.Board
import app.hudson.domain.BoardState
import app.hudson.domain.Boards
import app.hudson.domain.Departure
import app.hudson.domain.Favorite
import app.hudson.domain.Ink
import app.hudson.domain.TimeText
import app.hudson.notify.LiveNotifier
import app.hudson.widget.PosterRenderer
import app.hudson.widget.Widgets
import app.hudson.widget.TrainWidgetReceiver
import app.hudson.work.LiveSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val APP_REFRESH_MS = 30_000L
private val NightInk = Color(0xFF141414)

private enum class Screen { HOME, SETTINGS, ADD }

/** The whole app: home (just the trains), settings behind the gear, and adding a station. */
@Composable
fun HomeScreen(askNotificationsOnStart: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by Graph.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    var feed by remember { mutableStateOf<FeedSnapshot?>(null) }
    var screenName by rememberSaveable { mutableStateOf(Screen.HOME.name) }
    val screen = Screen.valueOf(screenName)

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        feed = Graph.feed.cached()
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                Graph.feed.refresh(maxAgeMs = 10_000).onSuccess { feed = it }
                delay(APP_REFRESH_MS)
            }
        }
    }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }

    fun notificationsAllowed() =
        ContextCompat.checkSelfPermission(context, MainActivity.NOTIFICATION_PERMISSION) == PackageManager.PERMISSION_GRANTED
    // What became of the last attempt to start tracking, so a tap is never met with silence.
    var liveResult by remember { mutableStateOf<LiveNotifier.Result?>(null) }
    fun startTracking() = scope.launch {
        val st = settings ?: return@launch
        liveResult = LiveSession.start(context, st.appFavorite)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startTracking()
        else {
            // Once refused, Android stops showing its dialog; the switch is then only in Settings.
            liveResult = LiveNotifier.Result.BLOCKED
            LiveNotifier.openNotificationSettings(context)
        }
    }
    LaunchedEffect(askNotificationsOnStart) {
        if (askNotificationsOnStart && !notificationsAllowed()) permission.launch(MainActivity.NOTIFICATION_PERMISSION)
    }

    fun save(transform: (Settings) -> Settings) = scope.launch {
        Graph.settings.update(transform)
        Widgets.repaintAll(context)
    }

    BackHandler(enabled = screen != Screen.HOME) {
        screenName = if (screen == Screen.ADD) Screen.SETTINGS.name else Screen.HOME.name
    }

    val s = SwissTheme.colors
    Box(Modifier.fillMaxSize().background(s.paper)) {
        val st = settings ?: return@Box
        // Going deeper slides in from the right, coming back from the left: you always know where you are.
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (slideInHorizontally(Motion.move()) { it / 6 * dir } + fadeIn(Motion.enter)) togetherWith
                    (slideOutHorizontally(Motion.move()) { -it / 6 * dir } + fadeOut(Motion.exit))
            },
            label = "screen",
        ) { target ->
            when (target) {
                Screen.HOME -> Home(
                    settings = st, feed = feed, now = now,
                    onPick = { i -> save { it.copy(appFavorite = i) } },
                    onTrack = {
                        if (notificationsAllowed()) startTracking()
                        else permission.launch(MainActivity.NOTIFICATION_PERMISSION)
                    },
                    onStop = { liveResult = null; scope.launch { LiveSession.stop(context) } },
                    liveResult = liveResult,
                    onSettings = { screenName = Screen.SETTINGS.name },
                )
                Screen.SETTINGS -> SettingsScreen(
                    settings = st,
                    onBack = { screenName = Screen.HOME.name },
                    onAdd = { screenName = Screen.ADD.name },
                    onReorder = { list ->
                        save { cur ->
                            val selectedId = cur.favorites.getOrNull(Math.floorMod(cur.appFavorite, cur.favorites.size.coerceAtLeast(1)))?.id
                            cur.copy(favorites = list, appFavorite = list.indexOfFirst { it.id == selectedId }.coerceAtLeast(0))
                        }
                    },
                    onRemove = { id -> save { cur -> cur.copy(favorites = cur.favorites.filterNot { it.id == id }, appFavorite = 0) } },
                    onMinutes = { m -> save { it.copy(liveMinutes = m) } },
                    onMatchPhone = { on -> save { it.copy(matchPhone = on) } },
                )
                Screen.ADD -> AddScreen(
                    onClose = { screenName = Screen.SETTINGS.name },
                    onSave = { f ->
                        save { it.copy(favorites = it.favorites + f) }
                        screenName = Screen.SETTINGS.name
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Home

@Composable
private fun Home(settings: Settings, feed: FeedSnapshot?, now: Long, onPick: (Int) -> Unit, onTrack: () -> Unit, onStop: () -> Unit, liveResult: LiveNotifier.Result?, onSettings: () -> Unit) {
    val s = SwissTheme.colors
    val favorites = settings.favorites.ifEmpty { Favorite.defaults }
    val selected = Math.floorMod(settings.appFavorite, favorites.size)
    val board = Boards.build(favorites[selected], feed, now)
    val tracking = settings.liveUntilMs > now

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("PATH", style = Type.wordmark, color = s.ink, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings", tint = s.ink)
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(12.dp))
            StationSwitcher(favorites, selected, onPick)
            Spacer(Modifier.height(16.dp))
            // Switching station slides the whole board the way you moved along the row of stations.
            AnimatedContent(
                targetState = selected,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(Motion.move()) { it / 5 * dir } + fadeIn(Motion.enter)) togetherWith
                        (slideOutHorizontally(Motion.move()) { -it / 5 * dir } + fadeOut(Motion.exit))
                },
                label = "station",
            ) { shown ->
                val shownBoard = if (shown == selected) board else Boards.build(favorites[shown.coerceIn(favorites.indices)], feed, now)
                Column(Modifier.animateContentSize(Motion.resize)) {
                    Hero(shownBoard, now, height = 250)
                    Spacer(Modifier.height(20.dp))
                    if (shownBoard.later.isNotEmpty()) {
                        Text("THEN", style = Type.label, color = s.muted, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                        shownBoard.later.take(4).forEachIndexed { i, d ->
                            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(s.rule))
                            TrainRow(d, now)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        Column(Modifier.animateContentSize(Motion.resize)) { LiveNote(liveResult, tracking) }
        AnimatedContent(targetState = tracking, transitionSpec = { fadeIn(Motion.enter) togetherWith fadeOut(Motion.exit) }, label = "tracking") { on ->
            if (on) TrackingStrip(settings.liveUntilMs, onStop) else TrackButton(onTrack)
        }
        Text(
            if (board.state == BoardState.STALE) "NO SIGNAL · " + TimeText.updated(board.fetchedAtMs).uppercase() else TimeText.updated(board.fetchedAtMs).uppercase(),
            style = Type.label.copy(fontSize = 11.sp),
            color = if (board.state == BoardState.STALE) s.alert else s.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp),
        )
    }
}

/** Your stations as big blocks: filled is the one shown. Two or three share the width; more scroll. */
@Composable
private fun StationSwitcher(favorites: List<Favorite>, selected: Int, onPick: (Int) -> Unit) {
    if (favorites.size < 2) return
    val fits = favorites.size <= 2
    val row = if (fits) Modifier.fillMaxWidth() else Modifier.horizontalScroll(rememberScrollState())
    Row(row, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        favorites.forEachIndexed { i, f ->
            StationBlock(
                title = f.station.short.uppercase(),
                subtitle = f.subtitle.uppercase(),
                selected = i == selected,
                onClick = { onPick(i) },
                modifier = if (fits) Modifier.weight(1f) else Modifier.widthIn(min = 120.dp),
            )
        }
    }
}

@Composable
private fun StationBlock(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val s = SwissTheme.colors
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = animateColorAsState(if (selected) s.ink else s.ink.copy(alpha = 0f), Motion.color, label = "block").value,
        contentColor = animateColorAsState(if (selected) s.paper else s.ink, Motion.color, label = "block text").value,
        border = BorderStroke(2.dp, s.ink),
        modifier = modifier.height(if (subtitle.isEmpty()) 52.dp else 64.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = if (subtitle.isEmpty()) Alignment.CenterHorizontally else Alignment.Start) {
            Text(title, style = Type.block, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(subtitle, style = Type.label.copy(fontSize = 10.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The next train as a poster: its line colour, its destination, and the minutes as a giant number. */
@Composable
private fun Hero(board: Board, now: Long, height: Int) {
    val s = SwissTheme.colors
    val next = board.next
    val colors = next?.colors.orEmpty()
    // Following the phone's colours, the card is a Material You container and the line is a bar down its edge.
    val phone = s.hero != null && s.onHero != null && next != null
    val bg = if (phone) s.hero!! else colors.firstOrNull()?.let { Color(it.toInt()) } ?: s.ink
    val ink = when {
        next == null -> s.paper
        phone -> s.onHero!!
        Ink.wantsDarkText(colors.first()) -> NightInk
        else -> Color.White
    }
    val description = PosterRenderer.describe(board, now)
    // When the next train is on another line, the card eases to its colour rather than snapping.
    val ground by animateColorAsState(bg, Motion.color, label = "hero ground")
    Box(
        Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(28.dp)).background(ground)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        if (phone) Box(Modifier.align(Alignment.CenterStart)) { LineBar(colors, width = 8, height = height) }
        colors.getOrNull(1)?.takeIf { !phone }?.let { second ->
            Canvas(Modifier.fillMaxSize()) {
                val path = Path().apply {
                    moveTo(0f, size.height * 0.72f)
                    lineTo(size.width, size.height * 0.30f)
                    lineTo(size.width, size.height * 0.72f)
                    lineTo(0f, size.height * 1.14f)
                    close()
                }
                drawPath(path, Color(second.toInt()))
            }
        }
        if (next == null) {
            Column(Modifier.fillMaxSize().padding(22.dp)) {
                Text(board.favorite.compact.uppercase(), style = Type.label, color = ink.copy(alpha = 0.75f))
                Spacer(Modifier.weight(1f))
                Text(PosterRenderer.emptyTitle(board.state), style = Type.title.copy(fontSize = 44.sp, lineHeight = 46.sp), color = ink)
                Text(PosterRenderer.emptyHint(board.state), style = Type.body, color = ink.copy(alpha = 0.75f))
            }
            return@Box
        }
        val minutes = TimeText.minutes(next.atMs, now)
        val big = if (minutes == 0L) "NOW" else minutes.toString()
        // The minutes roll like a departure board: the old number leaves upward as the new one arrives from below.
        AnimatedContent(
            targetState = big,
            transitionSpec = {
                (slideInVertically(Motion.move()) { it / 3 } + fadeIn(Motion.enter)) togetherWith
                    (slideOutVertically(Motion.move()) { -it / 3 } + fadeOut(Motion.exit)) using SizeTransform(clip = false)
            },
            contentAlignment = Alignment.BottomEnd,
            label = "minutes",
            modifier = Modifier.align(Alignment.BottomEnd),
        ) { value ->
            val valueSize = when {
                value == "NOW" -> 104
                value.length >= 2 -> 168
                else -> 230
            }
            Text(
                value,
                style = Type.giant.copy(fontSize = valueSize.sp, lineHeight = valueSize.sp),
                color = ink,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.offset(x = 12.dp, y = (valueSize * 0.2f).dp),
            )
        }
        Column(Modifier.padding(start = 22.dp, top = 20.dp, end = 22.dp)) {
            Text(board.favorite.station.short.uppercase(), style = Type.label, color = ink)
            val dest = "→ " + next.label.substringBefore(" via ").uppercase()
            val destSize = when {
                dest.length <= 8 -> 34
                dest.length <= 12 -> 28
                else -> 22
            }
            Text(dest, style = Type.title.copy(fontSize = destSize.sp, lineHeight = (destSize + 4).sp), color = ink, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.72f))
        }
        Text(
            (if (minutes == 0L) "LEAVING · " else "MIN · LEAVES ") + TimeText.clock(next.atMs),
            style = Type.label,
            color = ink.copy(alpha = 0.8f),
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 22.dp, bottom = 20.dp),
        )
    }
}

@Composable
private fun TrainRow(d: Departure, now: Long) {
    val s = SwissTheme.colors
    Row(Modifier.fillMaxWidth().height(60.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        LineBar(d.colors, width = 8, height = 34)
        Spacer(Modifier.width(14.dp))
        Text(TimeText.clock(d.atMs), style = Type.time, color = s.ink)
        Spacer(Modifier.width(14.dp))
        Text(d.label.uppercase(), style = Type.label, color = s.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(TimeText.countdown(d.atMs, now).uppercase(), style = Type.bodyBold, color = s.ink)
    }
}

@Composable
private fun LineBar(colors: List<Long>, width: Int, height: Int) {
    Column(Modifier.size(width.dp, height.dp).clip(RoundedCornerShape(2.dp))) {
        colors.ifEmpty { listOf(0xFF8A8A8AL) }.take(2).forEach { c -> Box(Modifier.fillMaxWidth().weight(1f).background(Color(c.toInt()))) }
    }
}

/** Says why tracking is not on the lock screen, and takes the user to the switch that fixes it. */
@Composable
private fun LiveNote(result: LiveNotifier.Result?, tracking: Boolean) {
    val s = SwissTheme.colors
    val context = LocalContext.current
    val (text, action) = when {
        result == LiveNotifier.Result.BLOCKED -> "Notifications are off for Hudson. Turn them on" to { LiveNotifier.openNotificationSettings(context) }
        result == LiveNotifier.Result.FAILED -> "Your phone would not show the tracking card. Check notifications" to { LiveNotifier.openNotificationSettings(context) }
        tracking && (result == LiveNotifier.Result.NOT_PROMOTED || !LiveNotifier.canPromote(context)) ->
            "It is in your notifications. For the lock screen, allow Live Updates" to { LiveNotifier.openPromotionSettings(context) }
        else -> return
    }
    Surface(onClick = action, color = Color.Transparent, contentColor = s.ink, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = Type.body.copy(fontSize = 14.sp, lineHeight = 19.sp), modifier = Modifier.weight(1f))
            Icon(painterResource(R.drawable.ic_chevron), contentDescription = null, tint = s.muted, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun TrackButton(onClick: () -> Unit) {
    val s = SwissTheme.colors
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = s.ink, contentColor = s.paper, modifier = Modifier.fillMaxWidth().height(64.dp)) {
        Box(contentAlignment = Alignment.Center) { Text("TRACK ON LOCK SCREEN", style = Type.block.copy(fontSize = 16.sp)) }
    }
}

@Composable
private fun TrackingStrip(untilMs: Long, onStop: () -> Unit) {
    val s = SwissTheme.colors
    Row(
        Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(16.dp)).background(s.ink).padding(start = 18.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(Color(0xFFD93A30)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            // Only claim the lock screen when the phone has actually allowed it there.
            Text(if (LiveNotifier.canPromote(LocalContext.current)) "ON LOCK SCREEN" else "IN NOTIFICATIONS", style = Type.block, color = s.paper)
            Text("UNTIL ${TimeText.clock(untilMs)}", style = Type.label.copy(fontSize = 10.sp), color = s.paper.copy(alpha = 0.7f))
        }
        Surface(onClick = onStop, shape = RoundedCornerShape(12.dp), color = s.paper, contentColor = s.ink, modifier = Modifier.height(48.dp).width(84.dp)) {
            Box(contentAlignment = Alignment.Center) { Text("STOP", style = Type.block) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Settings

@Composable
private fun SettingsScreen(
    settings: Settings,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onReorder: (List<Favorite>) -> Unit,
    onRemove: (String) -> Unit,
    onMinutes: (Int) -> Unit,
    onMatchPhone: (Boolean) -> Unit,
) {
    val s = SwissTheme.colors
    val context = LocalContext.current
    var lockHelp by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        TopBar("SETTINGS", R.drawable.ic_back, "Back", onBack)

        SectionLabel("MY STATIONS")
        Reorderable(settings.favorites, onReorder, onRemove)
        Spacer(Modifier.height(8.dp))
        OutlineBlock("ADD A STATION", R.drawable.ic_add, onAdd)
        Text("Drag ≡ to reorder. The widget steps through them in this order.", style = Type.body.copy(fontSize = 14.sp), color = s.muted, modifier = Modifier.padding(top = 8.dp, start = 4.dp))

        SectionLabel("COLORS")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StationBlock(title = "LINE COLORS", subtitle = "RED, GREEN, BLUE…", selected = !settings.matchPhone, onClick = { onMatchPhone(false) }, modifier = Modifier.weight(1f))
            StationBlock(title = "MATCH MY PHONE", subtitle = "FROM YOUR WALLPAPER", selected = settings.matchPhone, onClick = { onMatchPhone(true) }, modifier = Modifier.weight(1f))
        }

        SectionLabel("LOCK SCREEN TRACKING LASTS")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15, 30, 45, 60).forEach { m ->
                StationBlock(title = "$m MIN", subtitle = "", selected = settings.liveMinutes == m, onClick = { onMinutes(m) }, modifier = Modifier.weight(1f))
            }
        }
        if (Build.VERSION.SDK_INT >= 36 && !LiveNotifier.canPromote(context)) {
            Spacer(Modifier.height(8.dp))
            LinkRow("Allow tracking on the lock screen") {
                LiveNotifier.openPromotionSettings(context)
            }
        }

        SectionLabel("WIDGET")
        LinkRow("Add to home screen") {
            val manager = AppWidgetManager.getInstance(context)
            if (manager.isRequestPinAppWidgetSupported) manager.requestPinAppWidget(ComponentName(context, TrainWidgetReceiver::class.java), null, null)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(s.rule))
        Column(Modifier.animateContentSize()) {
            LinkRow("Put it on the lock screen") { lockHelp = !lockHelp }
            if (lockHelp) {
                Text(
                    "1. Install Good Lock from the Galaxy Store.\n2. In Good Lock, add LockStar.\n3. Open LockStar, edit your lock screen, tap Add widget, pick PATH trains.",
                    style = Type.body,
                    color = s.ink,
                    modifier = Modifier.padding(start = 4.dp, bottom = 12.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Stations with a drag handle; a screen reader gets "Move up" and "Move down" actions instead. */
@Composable
private fun Reorderable(favorites: List<Favorite>, onReorder: (List<Favorite>) -> Unit, onRemove: (String) -> Unit) {
    val s = SwissTheme.colors
    var order by remember(favorites) { mutableStateOf(favorites) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dy by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { 64.dp.toPx() }
    fun move(id: String, by: Int): Boolean {
        val i = order.indexOfFirst { it.id == id }
        val j = i + by
        if (i < 0 || j !in order.indices) return false
        order = order.toMutableList().apply { add(j, removeAt(i)) }
        return true
    }
    Column(Modifier.clip(RoundedCornerShape(16.dp)).background(s.block)) {
        order.forEachIndexed { index, f ->
            key(f.id) {
                val isDragged = dragging == f.id
                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(s.rule))
                Row(
                    Modifier.fillMaxWidth().height(64.dp).zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragged) dy else 0f }
                        .background(if (isDragged) s.rule else s.block)
                        .semantics(mergeDescendants = true) {
                            customActions = listOf(
                                CustomAccessibilityAction("Move up") { move(f.id, -1).also { if (it) onReorder(order) } },
                                CustomAccessibilityAction("Move down") { move(f.id, 1).also { if (it) onReorder(order) } },
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(56.dp).pointerInput(f.id) {
                            detectDragGestures(
                                onDragStart = { dragging = f.id; dy = 0f },
                                onDragEnd = { dragging = null; dy = 0f; onReorder(order) },
                                onDragCancel = { dragging = null; dy = 0f },
                            ) { change, amount ->
                                change.consume()
                                dy += amount.y
                                if (dy > rowPx / 2 && move(f.id, 1)) dy -= rowPx
                                else if (dy < -rowPx / 2 && move(f.id, -1)) dy += rowPx
                            }
                        },
                        contentAlignment = Alignment.Center,
                    ) { Icon(painterResource(R.drawable.ic_drag), contentDescription = null, tint = s.muted) }
                    Column(Modifier.weight(1f)) {
                        Text(f.station.short.uppercase(), style = Type.block, color = s.ink)
                        Text(f.subtitle.uppercase(), style = Type.label.copy(fontSize = 10.sp), color = s.muted)
                    }
                    if (order.size > 1) {
                        IconButton(onClick = { onRemove(f.id) }, modifier = Modifier.size(48.dp)) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = "Remove ${f.compact}", tint = s.ink)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Add a station

@Composable
private fun AddScreen(onClose: () -> Unit, onSave: (Favorite) -> Unit) {
    val s = SwissTheme.colors
    var station by remember { mutableStateOf<Station?>(null) }
    var direction by remember { mutableStateOf<Direction?>(null) }
    val st = station
    val directions = st?.let { Network.directions(it) }.orEmpty()
    val dir = when {
        directions.size == 1 -> directions.first()
        direction != null -> direction
        st != null -> if (st.side == Side.NJ) Direction.TO_NY else Direction.TO_NJ
        else -> null
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp, vertical = 8.dp)) {
        TopBar("ADD A STATION", R.drawable.ic_close, "Close", onClose)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SectionLabel("1 · LEAVING FROM")
            for (side in Side.entries) {
                Text(if (side == Side.NJ) "New Jersey" else "New York", style = Type.body.copy(fontSize = 13.sp), color = s.muted, modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Station.entries.filter { it.side == side }.forEach { option ->
                        StationBlock(option.short.uppercase(), "", option == station, { station = option; direction = null }, Modifier.widthIn(min = 64.dp))
                    }
                }
            }
            if (st != null) {
                SectionLabel("2 · GOING TO")
                if (directions.size == 1) {
                    Text("Trains from ${st.short} only go to ${directions.first().title}.", style = Type.body, color = s.ink, modifier = Modifier.padding(start = 4.dp))
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Direction.TO_NY, Direction.TO_NJ).forEach { d ->
                            StationBlock(d.title.uppercase(), "", dir == d, { direction = d }, Modifier.weight(1f))
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        val ready = st != null && dir != null
        Surface(
            onClick = { if (st != null) onSave(Favorite.create(st, dir, emptyList(), "f" + System.currentTimeMillis())) },
            enabled = ready,
            shape = RoundedCornerShape(16.dp),
            color = if (ready) s.ink else s.block,
            contentColor = if (ready) s.paper else s.muted,
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(if (st == null || dir == null) "PICK A STATION" else "SAVE ${st.short.uppercase()} → ${dir.short}", style = Type.block.copy(fontSize = 16.sp))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// Shared pieces

@Composable
private fun TopBar(title: String, icon: Int, iconLabel: String, onIcon: () -> Unit) {
    val s = SwissTheme.colors
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onIcon, modifier = Modifier.size(48.dp)) { Icon(painterResource(icon), contentDescription = iconLabel, tint = s.ink) }
        Spacer(Modifier.width(4.dp))
        Text(title, style = Type.title, color = s.ink, modifier = Modifier.semantics { heading() })
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = Type.label, color = SwissTheme.colors.muted, modifier = Modifier.padding(start = 4.dp, top = 24.dp, bottom = 10.dp).semantics { heading() })
}

@Composable
private fun OutlineBlock(text: String, icon: Int, onClick: () -> Unit) {
    val s = SwissTheme.colors
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = Color.Transparent, contentColor = s.ink, border = BorderStroke(2.dp, s.ink), modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = Type.block)
        }
    }
}

@Composable
private fun LinkRow(text: String, onClick: () -> Unit) {
    val s = SwissTheme.colors
    Surface(onClick = onClick, color = Color.Transparent, contentColor = s.ink, modifier = Modifier.fillMaxWidth().height(60.dp)) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = Type.bodyBold.copy(fontSize = 17.sp), modifier = Modifier.weight(1f))
            Icon(painterResource(R.drawable.ic_chevron), contentDescription = null, tint = s.muted, modifier = Modifier.size(20.dp))
        }
    }
}
