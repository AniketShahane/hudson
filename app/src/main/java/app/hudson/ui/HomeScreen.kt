package app.hudson.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
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
import app.hudson.domain.TimeText
import app.hudson.notify.LiveNotifier
import app.hudson.widget.TrainWidget
import app.hudson.widget.TrainWidgetReceiver
import app.hudson.work.LiveSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How often the open app refreshes the feed. */
private const val APP_REFRESH_MS = 30_000L

@Composable
fun HomeScreen(askNotificationsOnStart: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by Graph.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    var feed by remember { mutableStateOf<FeedSnapshot?>(null) }
    var loading by remember { mutableStateOf(false) }
    var editorOpen by remember { mutableStateOf(false) }

    // Refresh while the app is on screen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        feed = Graph.feed.cached()
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                loading = true
                Graph.feed.refresh(maxAgeMs = 10_000).onSuccess { feed = it }
                loading = false
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

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scope.launch { settings?.let { LiveSession.start(context, it.appFavorite) } }
    }
    fun notificationsAllowed() =
        ContextCompat.checkSelfPermission(context, MainActivity.NOTIFICATION_PERMISSION) == PackageManager.PERMISSION_GRANTED
    LaunchedEffect(askNotificationsOnStart) {
        if (askNotificationsOnStart && !notificationsAllowed()) permission.launch(MainActivity.NOTIFICATION_PERMISSION)
    }

    fun save(transform: (Settings) -> Settings) = scope.launch {
        Graph.settings.update(transform)
        TrainWidget().updateAll(context)
    }

    val s = settings
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        if (s == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
            return@Surface
        }
        val favorites = s.favorites.ifEmpty { Favorite.defaults }
        val selected = Math.floorMod(s.appFavorite, favorites.size)
        val board = Boards.build(favorites[selected], feed, now)
        val liveOn = s.liveUntilMs > now

        LazyColumn(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "title") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("PATH trains", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                        Text(
                            TimeText.updated(feed?.fetchedAtMs),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (board.state == BoardState.STALE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (loading) LoadingIndicator(Modifier.size(36.dp))
                }
            }
            item(key = "chips") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(favorites, key = { _, f -> f.id }) { i, f ->
                        FilterChip(
                            selected = i == selected,
                            onClick = { save { it.copy(appFavorite = i) } },
                            label = { Text(f.compact) },
                        )
                    }
                }
            }
            item(key = "hero") { HeroCard(board, now) }
            item(key = "later") { LaterCard(board) }
            item(key = "live") {
                LiveCardSection(
                    liveOn = liveOn,
                    minutes = s.liveMinutes,
                    onStart = {
                        if (notificationsAllowed()) scope.launch { LiveSession.start(context, selected) }
                        else permission.launch(MainActivity.NOTIFICATION_PERMISSION)
                    },
                    onStop = { scope.launch { LiveSession.stop(context) } },
                    onMinutes = { m -> save { it.copy(liveMinutes = m) } },
                )
            }
            item(key = "favorites-title") { SectionTitle("Favorites", "Tap the widget to step through these, in this order.") }
            itemsIndexed(favorites, key = { _, f -> "fav-" + f.id }) { i, f ->
                FavoriteRow(
                    favorite = f,
                    canMoveUp = i > 0,
                    canDelete = favorites.size > 1,
                    onUp = { save { st -> st.copy(favorites = st.favorites.toMutableList().apply { add(i - 1, removeAt(i)) }) } },
                    onDelete = { save { st -> st.copy(favorites = st.favorites.filterNot { it.id == f.id }, appFavorite = 0) } },
                )
            }
            item(key = "add") {
                FilledTonalButton(onClick = { editorOpen = true }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("Add a favorite", style = MaterialTheme.typography.titleMedium)
                }
            }
            item(key = "widget") { WidgetSection() }
        }
    }

    if (editorOpen) {
        FavoriteEditor(
            onDismiss = { editorOpen = false },
            onSave = { f ->
                editorOpen = false
                save { it.copy(favorites = it.favorites + f) }
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------

/** The next train, large: a slowly turning cookie holds the minutes, the countdown ticks beside it. */
@Composable
private fun HeroCard(board: Board, now: Long) {
    val next = board.next
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(22.dp)) {
            Text(board.favorite.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(board.favorite.subtitle, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(18.dp))
            if (next == null) {
                Text(
                    when (board.state) {
                        BoardState.NO_DATA -> "Loading trains…"
                        BoardState.STALE -> "No recent data. Check your connection."
                        else -> "No trains listed right now."
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                MinutesBadge(TimeText.minutes(next.atMs, now))
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LineDots(next.colors, 12)
                        Spacer(Modifier.width(8.dp))
                        Text(next.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    AnimatedContent(
                        targetState = countdownText(next.atMs, now),
                        transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(160)) },
                        label = "countdown",
                    ) { text -> Text(text, style = CountdownStyle.copy(fontSize = 52.sp, lineHeight = 56.sp)) }
                    Text("Leaves ${TimeText.clock(next.atMs)}", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

private fun countdownText(atMs: Long, now: Long): String {
    val s = ((atMs - now) / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun MinutesBadge(minutes: Long) {
    val turn = rememberInfiniteTransition(label = "turn")
    val angle by turn.animateFloat(0f, 360f, infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    Box(Modifier.size(96.dp).clearAndSetSemantics { contentDescription = if (minutes == 0L) "Due now" else "$minutes minutes" }, contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(96.dp)
                .graphicsLayer { rotationZ = angle }
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .background(MaterialTheme.colorScheme.primary),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (minutes == 0L) "Now" else "$minutes", color = MaterialTheme.colorScheme.onPrimary, fontSize = if (minutes == 0L) 26.sp else 38.sp, fontWeight = FontWeight.Black, lineHeight = 40.sp)
            if (minutes > 0) Text("min", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun LaterCard(board: Board) {
    val later = board.later
    if (later.isEmpty()) return
    Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            later.forEach { d -> DepartureRow(d) }
        }
    }
}

@Composable
private fun DepartureRow(d: Departure) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineDots(d.colors, 10)
        Spacer(Modifier.width(14.dp))
        Text(d.headsign, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(TimeText.clock(d.atMs), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LiveCardSection(liveOn: Boolean, minutes: Int, onStart: () -> Unit, onStop: () -> Unit, onMinutes: (Int) -> Unit) {
    val context = LocalContext.current
    var showMore by remember { mutableStateOf(false) }
    Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer)) {
        Column(Modifier.padding(20.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Commute Live Update", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(if (liveOn) "On the lock screen now" else "Next trains in the Now Bar", style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = { showMore = !showMore }) { Text(if (showMore) "Less" else "More") }
            }
            Spacer(Modifier.height(12.dp))
            if (liveOn) OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
            else Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Start for $minutes min") }
            if (showMore) {
                Spacer(Modifier.height(12.dp))
                Text("Lasts $minutes minutes", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = minutes.toFloat(),
                    onValueChange = { onMinutes((it / 5).toInt() * 5) },
                    valueRange = 10f..90f,
                    steps = 15,
                )
                if (Build.VERSION.SDK_INT >= 36 && !LiveNotifier.canPromote(context)) {
                    Text("Live Updates are off for this app, so the card stays in the shade.", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }) { Text("Allow Live Updates") }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(Modifier.padding(start = 4.dp, top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FavoriteRow(favorite: Favorite, canMoveUp: Boolean, canDelete: Boolean, onUp: () -> Unit, onDelete: () -> Unit) {
    Card(shape = MaterialTheme.shapes.medium, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(favorite.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(favorite.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (canMoveUp) TextButton(onClick = onUp) { Text("Move up") }
            if (canDelete) TextButton(onClick = onDelete) { Text("Remove") }
        }
    }
}

@Composable
private fun WidgetSection() {
    val context = LocalContext.current
    var showLock by remember { mutableStateOf(false) }
    Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(20.dp).animateContentSize()) {
            Text("Widget", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Resize it from a one-line strip to a tall card.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val manager = AppWidgetManager.getInstance(context)
                    if (manager.isRequestPinAppWidgetSupported) {
                        manager.requestPinAppWidget(ComponentName(context, TrainWidgetReceiver::class.java), null, null)
                    }
                }) { Text("Add to home screen") }
                OutlinedButton(onClick = { showLock = !showLock }) { Text("Lock screen") }
            }
            if (showLock) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "One UI only offers Samsung widgets on the lock screen. To add this one: install Good Lock from the Galaxy Store, " +
                        "add its LockStar module, edit your lock screen there and choose Add widget → PATH trains. " +
                        "The commute Live Update shows in the Now Bar without any of this.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun LineDots(colors: List<Long>, sizeDp: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        colors.take(2).forEach { c -> Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(Color(c.toInt()))) }
    }
}

// ---------------------------------------------------------------------------------------------

/** Pick a station, a direction (where it has two) and, optionally, which terminals to keep. */
@Composable
private fun FavoriteEditor(onDismiss: () -> Unit, onSave: (Favorite) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var station by remember { mutableStateOf(Station.GROVE) }
    var direction by remember { mutableStateOf<Direction?>(Direction.TO_NY) }
    var targets by remember { mutableStateOf(setOf<Station>()) }
    val directions = Network.directions(station)
    val effectiveDirection = if (directions.size == 1) directions.first() else direction

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("New favorite", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Text("Station", style = MaterialTheme.typography.labelLarge)
            for (side in Side.entries) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Station.entries.filter { it.side == side }.forEach { st ->
                        FilterChip(selected = st == station, onClick = { station = st; targets = emptySet() }, label = { Text(st.short) })
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Direction", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            val options: List<Direction?> = if (directions.size == 1) directions else listOf(Direction.TO_NY, Direction.TO_NJ, null)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, d ->
                    SegmentedButton(
                        selected = effectiveDirection == d,
                        onClick = { direction = d; targets = emptySet() },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    ) { Text(d?.let { "To ${it.short}" } ?: "Both") }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Only trains to (optional)", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Network.terminals
                    .filter { it != station }
                    .filter { t ->
                        when (effectiveDirection) {
                            Direction.TO_NY -> t.side == Side.NY
                            Direction.TO_NJ -> t.side == Side.NJ
                            null -> true
                        }
                    }
                    .forEach { t ->
                        FilterChip(selected = t in targets, onClick = { targets = if (t in targets) targets - t else targets + t }, label = { Text(t.short) })
                    }
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onSave(Favorite.create(station, effectiveDirection, targets.toList(), "f" + System.currentTimeMillis())) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("Save", style = MaterialTheme.typography.titleMedium) }
        }
    }
}
