package app.hudson.domain

import app.hudson.core.Station

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
 * The first train drawn as a track it is travelling along: [progress] of [max] is how close it
 * is to leaving, over the last [LivePlan.APPROACH_WINDOW_MS]. [colors] are its line colours.
 */
data class Approach(val progress: Int, val max: Int, val colors: List<Long>)

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
    /** Clock times only, so it stays true however long the card sits unrefreshed: "Leaves 8:46 · then 8:52, 9:01". */
    val detail: String = summary,
    val approach: Approach? = null,
    /** A few letters for the card's coloured tile: the destination's station code, "WTC". */
    val tile: String? = null,
)

object LivePlan {
    const val TICK_MS = 45_000L
    const val APPROACH_WINDOW_MS = 10 * 60_000L
    private const val APPROACH_STEPS = 600
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
            detail = when {
                first == null -> summary
                else -> buildString {
                    append("Leaves ").append(TimeText.clock(first.atMs))
                    val rest = trains.drop(1)
                    if (rest.isNotEmpty()) append(" · then ").append(rest.joinToString(", ") { TimeText.clock(it.atMs) })
                }
            },
            approach = first?.let {
                val left = (it.atMs - nowMs).coerceIn(0, APPROACH_WINDOW_MS)
                Approach(
                    progress = ((APPROACH_WINDOW_MS - left) * APPROACH_STEPS / APPROACH_WINDOW_MS).toInt(),
                    max = APPROACH_STEPS,
                    colors = it.colors,
                )
            },
            tile = first?.let { d ->
                val name = d.label.substringBefore(" via ")
                Station.entries.firstOrNull { it.short.equals(name, ignoreCase = true) }?.code ?: name.take(3).uppercase()
            },
        )
    }

    /** Whether the session should keep running at [nowMs]. */
    fun active(endsAtMs: Long, nowMs: Long): Boolean = nowMs < endsAtMs

    /** When the next refresh of a running session is due. */
    /**
     * The regular beat, or sooner: the moment the first train drops off the board, so the card
     * moves on to the next train instead of counting the departed one into negative time.
     */
    fun nextTick(nowMs: Long, endsAtMs: Long, firstAtMs: Long? = null): Long? {
        val dropsAt = firstAtMs?.plus(Boards.GRACE_MS + 500)?.takeIf { it > nowMs }
        return minOf(nowMs + TICK_MS, dropsAt ?: Long.MAX_VALUE).takeIf { it < endsAtMs }
    }
}

/**
 * When the widget must next be repainted. It is one drawn picture, so nothing on it moves by
 * itself: it is repainted when "4" becomes "3", and when a train showing NOW has left and the
 * next one takes its place. With nothing to count down it still looks again every [IDLE_MS],
 * so a widget that says "no trains" or "no signal" recovers without being tapped.
 */
object Redraw {
    const val IDLE_MS = 120_000L

    /** A repaint also fetches when the trains it would show are older than this. */
    const val FETCH_IF_OLDER_MS = 45_000L

    fun nextAt(boards: List<Board>, nowMs: Long): Long = boards
        .mapNotNull { it.next?.atMs }
        .map { at ->
            val left = at - nowMs
            if (left <= 60_000) at + Boards.GRACE_MS + 250
            else nowMs + (left % 60_000).let { if (it < 500) it + 60_000 else it } + 250
        }
        .filter { it > nowMs }
        .minOrNull() ?: (nowMs + IDLE_MS)

    fun shouldFetch(fetchedAtMs: Long?, nowMs: Long): Boolean = fetchedAtMs == null || nowMs - fetchedAtMs > FETCH_IF_OLDER_MS
}

/** Ink for text on a line colour: black on yellow, white on red, blue and green. */
object Ink {
    fun luminance(argb: Long): Double {
        fun ch(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
    }

    /**
     * True when black text reads clearly better than white on [argb]. White is preferred
     * (it is the poster look) unless black has a clear lead, so PATH red keeps white type.
     */
    fun wantsDarkText(argb: Long): Boolean {
        val l = luminance(argb)
        val onWhite = 1.05 / (l + 0.05)
        val onBlack = (l + 0.05) / 0.05
        return onBlack > onWhite * 1.4
    }
}
