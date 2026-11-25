package app.hudson.domain

/**
 * Decides the widget's composition from its real size in dp. Launchers hand out very different
 * cells (a one-row widget is about 50 dp tall on a phone held sideways and about 100 dp upright),
 * so nothing here is a fixed bucket: the poster keeps its shape and the space left over becomes
 * rows of later trains.
 */
object PosterLayout {
    enum class Kind {
        /** One low row: dark, a bar of line colour, the minutes at the right. */
        STRIP,
        /** Narrow or upright: the number sits bottom-left under the labels. */
        SQUARE,
        /** Landscape: the giant number bleeds off the right and bottom edges. */
        WIDE,
    }

    data class Plan(
        val kind: Kind,
        /** Height of the coloured poster; the rest, if any, is the list of later trains. */
        val posterHeight: Float,
        /** Later trains listed under the poster. */
        val rows: Int,
    )

    const val STRIP_BELOW = 96f
    const val ROW_HEIGHT = 30f
    const val ROWS_PADDING = 12f
    const val MAX_ROWS = 4

    fun plan(widthDp: Float, heightDp: Float, laterTrains: Int): Plan {
        if (heightDp < STRIP_BELOW) return Plan(Kind.STRIP, heightDp, 0)
        // The least height at which the poster still reads as a poster; rows only take what is beyond it.
        val posterAtLeast = if (widthDp >= 200f) 150f else widthDp.coerceIn(120f, 170f)
        val fit = ((heightDp - posterAtLeast - ROWS_PADDING) / ROW_HEIGHT).toInt()
        val rows = fit.coerceIn(0, minOf(laterTrains.coerceAtLeast(0), MAX_ROWS))
        val posterHeight = if (rows == 0) heightDp else heightDp - rows * ROW_HEIGHT - ROWS_PADDING
        val kind = if (widthDp >= 190f && widthDp >= posterHeight * 1.3f) Kind.WIDE else Kind.SQUARE
        return Plan(kind, posterHeight, rows)
    }
}
