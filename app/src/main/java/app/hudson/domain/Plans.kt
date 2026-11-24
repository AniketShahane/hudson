package app.hudson.domain

/**
 * When to fetch again. Widgets cannot tick on their own, so the countdown is drawn by a
 * system Chronometer and the rows show clock times; what goes stale is the *list*. It is
 * refreshed just after the first train leaves (so the hero moves on), and at least every
 * [MAX_GAP_MS] while a widget exists.
 */
object RefreshPlan {
    const val MIN_GAP_MS = 60_000L
    const val MAX_GAP_MS = 15 * 60_000L
    private const val AFTER_DEPARTURE_MS = 20_000L

    fun nextAt(boards: List<Board>, nowMs: Long): Long {
        val firstLeaves = boards.mapNotNull { it.next?.atMs }.minOrNull()
        val wanted = firstLeaves?.plus(AFTER_DEPARTURE_MS) ?: (nowMs + MAX_GAP_MS)
        return wanted.coerceIn(nowMs + MIN_GAP_MS, nowMs + MAX_GAP_MS)
    }
}

/** One countdown in the Live Update. */
data class LiveMetric(val label: String, val atMs: Long)

/**
 * The Live Update for a commute the user started: up to three trains as countdowns, which the
 * system animates by itself, so the card stays right between refreshes.
 */
data class LiveCard(
    val title: String,
    val metrics: List<LiveMetric>,
    /** Text for the status-bar chip when a countdown would not show: "Now" in the last two minutes. */
    val chipText: String?,
    /** The time the chip counts down to. */
    val chipWhenMs: Long?,
    /** One line for phones without MetricStyle and for the collapsed card. */
    val summary: String,
    val endsAtMs: Long,
)

object LivePlan {
    const val TICK_MS = 45_000L
    private const val CHIP_COUNTDOWN_MIN_MS = 2 * 60_000L

    fun card(board: Board, nowMs: Long, endsAtMs: Long): LiveCard {
        val trains = board.departures.take(3)
        val first = trains.firstOrNull()
        val summary = when {
            first == null && board.state == BoardState.NO_DATA -> "Checking trains…"
            first == null -> "No trains listed right now"
            else -> buildString {
                append(first.label).append(" · ").append(TimeText.countdown(first.atMs, nowMs))
                val rest = trains.drop(1)
                if (rest.isNotEmpty()) append(" · then ").append(rest.joinToString(", ") { TimeText.clock(it.atMs) })
            }
        }
        val chipText = first?.let { if (it.atMs - nowMs < CHIP_COUNTDOWN_MIN_MS) "Now" else null }
        return LiveCard(
            title = board.favorite.compact,
            metrics = trains.map { LiveMetric(it.label, it.atMs) },
            chipText = chipText,
            chipWhenMs = first?.atMs,
            summary = summary,
            endsAtMs = endsAtMs,
        )
    }

    /** Whether the session should keep running at [nowMs]. */
    fun active(endsAtMs: Long, nowMs: Long): Boolean = nowMs < endsAtMs

    /** When the next refresh of a running session is due. */
    fun nextTick(nowMs: Long, endsAtMs: Long): Long? = (nowMs + TICK_MS).takeIf { it < endsAtMs }
}
