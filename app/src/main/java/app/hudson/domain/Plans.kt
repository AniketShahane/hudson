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

/**
 * When a widget's big minutes number next changes. The poster widget is one drawn picture, so
 * it is redrawn exactly when "4" becomes "3"; inside the last minute it shows NOW until the
 * train leaves, which the list refresh handles.
 */
object Redraw {
    fun nextAt(boards: List<Board>, nowMs: Long): Long? = boards
        .mapNotNull { it.next?.atMs }
        .mapNotNull { at ->
            val left = at - nowMs
            if (left <= 60_000) null else nowMs + (left % 60_000).let { if (it < 500) it + 60_000 else it } + 250
        }
        .minOrNull()
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
