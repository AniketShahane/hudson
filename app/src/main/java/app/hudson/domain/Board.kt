package app.hudson.domain

import app.hudson.data.feed.Arrival
import app.hudson.data.feed.FeedSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One train on a board. */
data class Departure(
    /** Short destination for chips: "WTC", "33rd St", "Journal Sq via Hob". */
    val label: String,
    /** Full sign for roomy rows: "Journal Square via Hoboken". */
    val headsign: String,
    val atMs: Long,
    val colors: List<Long>,
)

enum class BoardState {
    /** Trains to show. */
    OK,
    /** Fresh data, but nothing listed (late night, a suspension). */
    EMPTY,
    /** Data older than [Boards.STALE_AFTER_MS]; trains shown are what was last known. */
    STALE,
    /** Nothing fetched yet. */
    NO_DATA,
}

/** Everything a widget, the app or a notification needs to draw one favorite. */
data class Board(
    val favorite: Favorite,
    val departures: List<Departure>,
    val state: BoardState,
    val fetchedAtMs: Long?,
) {
    val next: Departure? get() = departures.firstOrNull()
    val later: List<Departure> get() = departures.drop(1)
}

object Boards {
    /**
     * A train stays on the board this long after its time, as the doors are usually still open.
     * Kept short: ten seconds past its time a train is no longer one you can catch, and a card
     * still counting it (the lock-screen timer runs into negative time) is only noise.
     */
    const val GRACE_MS = 10_000L
    const val STALE_AFTER_MS = 10 * 60_000L

    fun build(favorite: Favorite, feed: FeedSnapshot?, nowMs: Long, limit: Int = 5): Board {
        if (feed == null) return Board(favorite, emptyList(), BoardState.NO_DATA, null)
        val targets = favorite.targetStations.toSet()
        val departures = feed.arrivals
            .asSequence()
            .filter { it.station == favorite.station }
            .filter { favorite.direction == null || it.direction == favorite.direction }
            .filter { targets.isEmpty() || it.target in targets }
            .filter { it.atMs >= nowMs - GRACE_MS }
            .sortedBy { it.atMs }
            // The feed can list one train under two messages a second apart; keep one.
            .distinctBy { "${it.target?.code ?: it.headsign}@${it.atMs / 20_000}" }
            .take(limit)
            .map { it.toDeparture() }
            .toList()
        val state = when {
            nowMs - feed.fetchedAtMs > STALE_AFTER_MS -> BoardState.STALE
            departures.isEmpty() -> BoardState.EMPTY
            else -> BoardState.OK
        }
        return Board(favorite, departures, state, feed.fetchedAtMs)
    }

    private fun Arrival.toDeparture(): Departure {
        val base = target?.short ?: headsign
        val via = Regex("via (\\w+)", RegexOption.IGNORE_CASE).find(headsign)?.groupValues?.get(1)
        val label = if (via != null && !base.contains(via, ignoreCase = true)) "$base via ${via.take(3)}" else base
        return Departure(label = label, headsign = headsign, atMs = atMs, colors = colors)
    }
}

/** Words and numbers for times, shared by the widget, the app and the notification. */
object TimeText {
    private val clock12 = DateTimeFormatter.ofPattern("h:mm", Locale.US)
    private val clockAmPm = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    /** "8:42" in the phone's zone. Absolute, so it is still right if the widget is not redrawn. */
    fun clock(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        clock12.format(Instant.ofEpochMilli(atMs).atZone(zone))

    /** "5:30 PM", for times that may be hours old, where morning and evening must not be confused. */
    fun clockAmPm(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        clockAmPm.format(Instant.ofEpochMilli(atMs).atZone(zone))

    /** Whole minutes until [atMs], rounded down; 0 means due now. */
    fun minutes(atMs: Long, nowMs: Long): Long = ((atMs - nowMs).coerceAtLeast(0)) / 60_000

    /** "Now", "1 min", "12 min". */
    fun countdown(atMs: Long, nowMs: Long): String = when (val m = minutes(atMs, nowMs)) {
        0L -> "Now"
        else -> "$m min"
    }

    /** "Updated 8:41" */
    fun updated(fetchedAtMs: Long?, zone: ZoneId = ZoneId.systemDefault()): String =
        if (fetchedAtMs == null) "Not updated yet" else "Updated ${clock(fetchedAtMs, zone)}"
}
