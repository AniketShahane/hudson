package app.hudson.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hudson.R
import app.hudson.core.Direction
import app.hudson.core.Geo
import app.hudson.core.Line
import app.hudson.core.Network
import app.hudson.core.Side
import app.hudson.core.Station
import app.hudson.data.Locator
import app.hudson.data.feed.FeedSnapshot
import app.hudson.domain.Alert
import app.hudson.domain.Alerts
import app.hudson.domain.Boards
import app.hudson.domain.Departure
import app.hudson.domain.Favorite
import app.hudson.domain.TimeText
import kotlinx.coroutines.delay

/** The trains leaving [station] toward [direction], soonest first. */
internal fun departures(station: Station, direction: Direction, feed: FeedSnapshot?, now: Long, limit: Int = 6): List<Departure> =
    Boards.build(Favorite("browse", station.code, direction.feedLabel), feed, now, limit).departures

/** Rows arrive one after another, each a beat behind the last, instead of all at once. */
@Composable
internal fun Modifier.arrive(index: Int): Modifier {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index.coerceAtMost(12) * 26L)
        shown.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 300f))
    }
    return graphicsLayer {
        alpha = shown.value.coerceIn(0f, 1f)
        translationY = (1f - shown.value) * 28.dp.toPx()
    }
}

// ---------------------------------------------------------------------------------------------
// Stations: every station, as a list or on the line map

@Composable
internal fun StationsScreen(
    feed: FeedSnapshot?, now: Long, alerts: List<Alert>,
    near: Locator.Result?, locating: Boolean,
    onNear: () -> Unit, onStation: (Station) -> Unit,
) {
    val s = SwissTheme.colors
    var map by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("STATIONS", style = Type.wordmark, color = s.ink, modifier = Modifier.weight(1f).semantics { heading() })
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Toggle("LIST", !map) { map = false }
                Toggle("MAP", map) { map = true }
            }
        }
        // Times from a feed that has gone quiet are still shown, but never without saying so.
        val stale = feed != null && now - feed.fetchedAtMs > Boards.STALE_AFTER_MS
        if (feed == null || stale) {
            Text(
                if (feed == null) "GETTING TIMES FROM PATH…" else "NO SIGNAL · " + TimeText.updated(feed.fetchedAtMs).uppercase(),
                style = Type.label.copy(fontSize = 11.sp), color = if (stale) s.alert else s.muted, modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        AnimatedContent(targetState = map, transitionSpec = { fadeIn(Motion.enter) togetherWith fadeOut(Motion.exit) }, label = "stations view") { showMap ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                if (showMap) {
                    LineMap(feed, now, onStation)
                    SectionLabel("LINES")
                    Line.entries.forEachIndexed { i, line -> LineStatus(line, alerts, Modifier.arrive(i)) }
                } else {
                    NearYou(near, locating, feed, now, alerts, onNear, onStation)
                    var index = 1
                    for (side in Side.entries) {
                        SectionLabel(if (side == Side.NJ) "NEW JERSEY" else "NEW YORK")
                        Station.entries.filter { it.side == side }.forEachIndexed { i, station ->
                            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(s.rule))
                            StationRow(station, feed, now, Alerts.disrupts(Alerts.at(station, alerts)), null, { onStation(station) }, Modifier.arrive(index++))
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun Toggle(text: String, selected: Boolean, onClick: () -> Unit) {
    val s = SwissTheme.colors
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = androidx.compose.animation.animateColorAsState(if (selected) s.ink else s.ink.copy(alpha = 0f), Motion.color, label = "toggle").value,
        contentColor = androidx.compose.animation.animateColorAsState(if (selected) s.paper else s.ink, Motion.color, label = "toggle text").value,
        border = androidx.compose.foundation.BorderStroke(2.dp, s.ink),
        modifier = Modifier.height(40.dp).semantics { this.contentDescription = "$text view" + if (selected) ", selected" else "" },
    ) {
        Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) { Text(text, style = Type.block.copy(fontSize = 13.sp)) }
    }
}

/**
 * The stations near you. A phone's position can be a couple of kilometres off, so rather than
 * name one station and risk being wrong, every station that could be the nearest is listed with
 * its distance. Whatever stops a position being found is said plainly, with the way to fix it.
 */
@Composable
private fun NearYou(near: Locator.Result?, locating: Boolean, feed: FeedSnapshot?, now: Long, alerts: List<Alert>, onNear: () -> Unit, onStation: (Station) -> Unit) {
    val s = SwissTheme.colors
    Column(Modifier.animateContentSize(Motion.resize)) {
        @Composable
        fun problem(text: String, button: String) {
            SectionLabel("NEAR YOU")
            Text(text, style = Type.body.copy(fontSize = 14.sp, lineHeight = 20.sp), color = s.ink, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
            OutlineBlock(button, R.drawable.ic_near, onNear)
        }
        when (near) {
            is Locator.Result.Found -> {
                val fix = near.fix
                val stations = Geo.near(fix.lat, fix.lon, fix.accuracyMetres)
                val rough = fix.accuracyMetres > 400 || fix.ageMs > 10 * 60_000
                SectionLabel(if (rough) "NEAR YOU · APPROXIMATE" else "NEAR YOU")
                if (stations.isEmpty()) {
                    Text("You are not near a PATH station.", style = Type.body.copy(fontSize = 14.sp), color = s.muted, modifier = Modifier.padding(start = 4.dp))
                } else {
                    Column(Modifier.clip(RoundedCornerShape(20.dp)).background(s.block).padding(horizontal = 12.dp)) {
                        stations.forEachIndexed { i, (station, metres) ->
                            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(s.rule))
                            StationRow(station, feed, now, Alerts.disrupts(Alerts.at(station, alerts)), "ABOUT ${Geo.distance(metres).uppercase()}", { onStation(station) })
                        }
                    }
                }
            }
            Locator.Result.NotAllowed -> problem("Hudson is not allowed to see where you are.", "ALLOW LOCATION")
            Locator.Result.SwitchedOff -> problem("Location is switched off on this phone.", "TURN ON LOCATION")
            Locator.Result.NoFix -> problem("Could not find where you are just now. This can happen indoors or underground.", if (locating) "LOOKING…" else "TRY AGAIN")
            null -> {
                Spacer(Modifier.height(8.dp))
                OutlineBlock(if (locating) "FINDING YOU…" else "FIND STATIONS NEAR ME", R.drawable.ic_near, onNear)
            }
        }
    }
}

/** One station: the lines that call there, its name, and the next train each way. */
@Composable
internal fun StationRow(station: Station, feed: FeedSnapshot?, now: Long, hasAlert: Boolean, note: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val s = SwissTheme.colors
    val next = Network.directions(station).associateWith { departures(station, it, feed, now, 1).firstOrNull() }
    val said = buildString {
        append(station.title)
        if (note != null) append(", ").append(note.lowercase())
        next.forEach { (d, t) -> append(", to ${d.title} ").append(t?.let { TimeText.countdown(it.atMs, now) } ?: "no train listed") }
        if (hasAlert) append(", service alert")
    }
    Row(
        modifier.fillMaxWidth().height(72.dp).clickable(onClickLabel = "Open ${station.title}", onClick = onClick).clearAndSetSemantics { contentDescription = said },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Line.serving(station).forEach { Box(Modifier.size(width = 8.dp, height = 10.dp).clip(RoundedCornerShape(2.dp)).background(Color(it.color.toInt()))) }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(station.short.uppercase(), style = Type.block.copy(fontSize = 18.sp), color = s.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (hasAlert) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(8.dp).clip(CircleShape).background(s.alert))
                }
            }
            if (note != null) Text(note, style = Type.label.copy(fontSize = 10.sp), color = s.muted)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            next.forEach { (direction, train) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("→ ${direction.short}", style = Type.label.copy(fontSize = 10.sp), color = s.muted)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        train?.let { TimeText.countdown(it.atMs, now).uppercase() } ?: "—",
                        style = Type.bodyBold.copy(fontSize = 14.sp, fontFeatureSettings = "tnum"), color = s.ink,
                        modifier = Modifier.width(54.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    )
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        Icon(painterResource(R.drawable.ic_chevron), contentDescription = null, tint = s.muted, modifier = Modifier.size(18.dp))
    }
}

/** A line and what PATH is saying about it, if anything. */
@Composable
private fun LineStatus(line: Line, alerts: List<Alert>, modifier: Modifier = Modifier) {
    val s = SwissTheme.colors
    val about = Alerts.on(line, alerts)
    Column(modifier.animateContentSize(Motion.resize)) {
        Row(Modifier.fillMaxWidth().height(52.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 8.dp, height = 30.dp).clip(RoundedCornerShape(2.dp)).background(Color(line.color.toInt())))
            Spacer(Modifier.width(14.dp))
            Text(line.title, style = Type.bodyBold.copy(fontSize = 15.sp), color = s.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (about.isEmpty()) "NO ALERTS" else if (about.size == 1) "1 ALERT" else "${about.size} ALERTS", style = Type.label.copy(fontSize = 10.sp), color = if (about.isEmpty()) s.muted else s.alert)
        }
        if (about.isNotEmpty()) AlertsBlock(about, Modifier.padding(bottom = 8.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// The line map

/** Where each station sits on the diagram, as fractions of its width and height. West is left, as on PATH's own map. */
private val spot: Map<Station, Offset> = mapOf(
    Station.NEWARK to Offset(0.08f, 0.76f),
    Station.HARRISON to Offset(0.22f, 0.76f),
    Station.JOURNAL_SQUARE to Offset(0.36f, 0.76f),
    Station.GROVE to Offset(0.50f, 0.76f),
    Station.EXCHANGE_PLACE to Offset(0.62f, 0.89f),
    Station.WTC to Offset(0.90f, 0.89f),
    Station.NEWPORT to Offset(0.60f, 0.60f),
    Station.HOBOKEN to Offset(0.60f, 0.36f),
    Station.CHRISTOPHER to Offset(0.82f, 0.50f),
    Station.NINTH to Offset(0.90f, 0.39f),
    Station.FOURTEENTH to Offset(0.90f, 0.29f),
    Station.TWENTY_THIRD to Offset(0.90f, 0.19f),
    Station.THIRTY_THIRD to Offset(0.90f, 0.09f),
)

/** Lines that share a stretch of track are drawn side by side: each is nudged by this many dp. */
private val nudge: Map<Line, Offset> = mapOf(
    Line.NWK_WTC to Offset(0f, -3f),
    Line.HOB_WTC to Offset(0f, 3f),
    Line.JSQ_33 to Offset(-3f, 3f),
    Line.HOB_33 to Offset(3f, 0f),
)

/** Which side of its dot a station's name goes, so names never sit on a line: dx, dy in dp from the dot. */
private val label: Map<Station, Offset> = mapOf(
    Station.NEWARK to Offset(0f, 26f), Station.HARRISON to Offset(0f, -26f), Station.JOURNAL_SQUARE to Offset(0f, 26f),
    Station.GROVE to Offset(-30f, -25f), Station.EXCHANGE_PLACE to Offset(0f, 26f), Station.WTC to Offset(0f, 26f),
    Station.NEWPORT to Offset(-48f, 0f), Station.HOBOKEN to Offset(-48f, 0f), Station.CHRISTOPHER to Offset(0f, 27f),
    Station.NINTH to Offset(-38f, 0f), Station.FOURTEENTH to Offset(-40f, 0f), Station.TWENTY_THIRD to Offset(-40f, 0f), Station.THIRTY_THIRD to Offset(-40f, 0f),
)

private fun Modifier.centredAt(x: Dp, y: Dp): Modifier = layout { measurable, _ ->
    val placeable = measurable.measure(Constraints())
    layout(0, 0) { placeable.place(x.roundToPx() - placeable.width / 2, y.roundToPx() - placeable.height / 2) }
}

/**
 * PATH as a diagram: the four lines draw themselves in, then each station shows the minutes to
 * its next train, pulsing when one is due now. Tap a station for its board.
 */
@Composable
private fun LineMap(feed: FeedSnapshot?, now: Long, onStation: (Station) -> Unit) {
    val s = SwissTheme.colors
    val drawn = remember { Animatable(0f) }
    LaunchedEffect(Unit) { drawn.animateTo(1f, tween(900)) }
    // Only animate at all while some station has a train due now; an idle map costs nothing.
    val anyDue = Station.entries.any { st -> Network.directions(st).any { d -> departures(st, d, feed, now, 1).firstOrNull()?.let { TimeText.minutes(it.atMs, now) == 0L } == true } }
    val pulse = if (anyDue) rememberInfiniteTransition(label = "due now").animateFloat(1f, 1.18f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse").value else 1f
    BoxWithConstraints(Modifier.fillMaxWidth().height(430.dp).clip(RoundedCornerShape(24.dp)).background(s.block)) {
        val w = maxWidth
        val h = maxHeight
        Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
            // The river the whole thing is named for.
            val river = Path().apply {
                moveTo(size.width * 0.685f, 0f)
                cubicTo(size.width * 0.70f, size.height * 0.3f, size.width * 0.67f, size.height * 0.6f, size.width * 0.74f, size.height)
                lineTo(size.width * 0.80f, size.height)
                cubicTo(size.width * 0.73f, size.height * 0.6f, size.width * 0.76f, size.height * 0.3f, size.width * 0.745f, 0f)
                close()
            }
            drawPath(river, Color(0xFF4D92FB).copy(alpha = 0.16f))
            val measure = PathMeasure()
            for (line in Line.entries) {
                val by = nudge.getValue(line)
                val path = Path()
                line.stations.forEachIndexed { i, st ->
                    val p = spot.getValue(st)
                    val x = p.x * size.width + by.x.dp.toPx()
                    val y = p.y * size.height + by.y.dp.toPx()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                measure.setPath(path, false)
                val part = Path()
                measure.getSegment(0f, measure.length * drawn.value, part, true)
                drawPath(part, Color(line.color.toInt()), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        Text("HUDSON", style = Type.label.copy(fontSize = 9.sp), color = s.muted, modifier = Modifier.centredAt(w * 0.725f, h * 0.70f).graphicsLayer { rotationZ = 82f })
        Station.entries.forEachIndexed { i, station ->
            val p = spot.getValue(station)
            val soonest = Network.directions(station).mapNotNull { departures(station, it, feed, now, 1).firstOrNull() }.minByOrNull { it.atMs }
            val minutes = soonest?.let { TimeText.minutes(it.atMs, now) }
            val appear = ((drawn.value - 0.35f - i * 0.03f) / 0.3f).coerceIn(0f, 1f)
            Box(
                Modifier.centredAt(w * p.x, h * p.y).size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClickLabel = "Open ${station.title}") { onStation(station) }
                    .semantics { contentDescription = station.title + (minutes?.let { m -> if (m == 0L) ", train due now" else ", next train in $m minutes" } ?: ", no train listed") },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(26.dp).graphicsLayer {
                        val scale = appear * if (minutes == 0L) pulse else 1f
                        scaleX = scale; scaleY = scale; alpha = appear
                    }.clip(CircleShape).background(s.ink),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when (minutes) { null -> "–"; 0L -> "0"; else -> minutes.coerceAtMost(99).toString() },
                        style = Type.block.copy(fontSize = 11.sp, fontFeatureSettings = "tnum"), color = s.paper,
                    )
                }
            }
            val off = label.getValue(station)
            Text(
                station.short.uppercase(), style = Type.label.copy(fontSize = 9.sp, letterSpacing = 0.08.sp * 10), color = s.ink, maxLines = 1,
                modifier = Modifier.centredAt(w * p.x + off.x.dp, h * p.y + off.y.dp).graphicsLayer { alpha = appear }.clearAndSetSemantics {},
            )
        }
        Text("MINUTES TO THE NEXT TRAIN", style = Type.label.copy(fontSize = 9.sp), color = s.muted, modifier = Modifier.align(Alignment.TopStart).padding(16.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// One station's board

@Composable
internal fun StationScreen(
    station: Station, feed: FeedSnapshot?, now: Long, alerts: List<Alert>, favorites: List<Favorite>,
    onBack: () -> Unit, onAdd: (Direction) -> Unit,
) {
    val s = SwissTheme.colors
    Column(Modifier.fillMaxSize().background(s.paper).windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        TopBar(station.short.uppercase(), R.drawable.ic_back, "Back", onBack)
        Row(Modifier.padding(start = 4.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Line.serving(station).forEachIndexed { i, line ->
                Box(Modifier.arrive(i).clip(RoundedCornerShape(8.dp)).background(Color(line.color.toInt())).padding(horizontal = 9.dp, vertical = 5.dp)) {
                    Text(line.code, style = Type.label.copy(fontSize = 10.sp), color = if (app.hudson.domain.Ink.wantsDarkText(line.color)) Color(0xFF141414) else Color.White)
                }
            }
        }
        if (alerts.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            AlertsBlock(alerts, Modifier.arrive(0))
        }
        var row = 2
        for (direction in Network.directions(station)) {
            val trains = departures(station, direction, feed, now)
            val saved = favorites.any { it.station == station && it.targets.isEmpty() && (it.direction == direction || it.direction == null) }
            Row(verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f)) { SectionLabel("TO ${direction.title.uppercase()}") }
                Surface(
                    onClick = { if (!saved) onAdd(direction) },
                    enabled = !saved,
                    shape = RoundedCornerShape(10.dp), color = Color.Transparent, contentColor = if (saved) s.muted else s.ink,
                    modifier = Modifier.padding(bottom = 4.dp).height(48.dp),
                ) {
                    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(if (saved) R.drawable.ic_check else R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (saved) "IN FAVORITES" else "ADD TO FAVORITES", style = Type.label.copy(fontSize = 11.sp))
                    }
                }
            }
            if (trains.isEmpty()) {
                Text(if (feed == null) "Getting times from PATH…" else "No trains listed right now.", style = Type.body, color = s.muted, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            } else {
                Column(Modifier.animateContentSize(Motion.resize)) {
                    trains.forEachIndexed { i, d ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(s.rule))
                        Box(Modifier.arrive(row++)) { TrainRow(d, now) }
                    }
                }
            }
        }
        Text(TimeText.updated(feed?.fetchedAtMs).uppercase(), style = Type.label.copy(fontSize = 11.sp), color = s.muted, modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 16.dp))
    }
}
