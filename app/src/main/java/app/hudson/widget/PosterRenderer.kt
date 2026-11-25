package app.hudson.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import app.hudson.R
import app.hudson.domain.Board
import app.hudson.domain.BoardState
import app.hudson.domain.Ink
import app.hudson.domain.TimeText

/**
 * Draws the Swiss-poster widget as one picture: the next train's line colour fills the card and
 * its minutes are a giant number bleeding off the edge. A widget cannot lay type out like this
 * (no overflow, no custom fonts in text), so it is painted; [Redraw] books a repaint each time
 * the number changes.
 *
 * Everything is laid out in dp; the canvas is scaled to pixels once.
 */
object PosterRenderer {
    enum class Layout { STRIP, SQUARE, CARD, TALL }

    const val RADIUS_DP = 28f
    private const val NIGHT = 0xFF141414.toInt()
    private const val PAPER = 0xFFF2EFE8.toInt()

    @Volatile private var display: Typeface? = null

    private fun display(context: Context): Typeface = display ?: (
        runCatching { ResourcesCompat.getFont(context, R.font.archivo_black) }.getOrNull() ?: Typeface.DEFAULT_BOLD
    ).also { display = it }

    private val sansBold: Typeface = Typeface.create(Typeface.SANS_SERIF, 700, false)
    private val sansMedium: Typeface = Typeface.create(Typeface.SANS_SERIF, 500, false)

    fun layoutFor(widthDp: Float, heightDp: Float): Layout = when {
        heightDp < 100f -> Layout.STRIP
        widthDp < 200f -> Layout.SQUARE
        heightDp >= 190f -> Layout.TALL
        else -> Layout.CARD
    }

    /** What a screen reader says for the picture. */
    fun describe(board: Board, nowMs: Long): String {
        val next = board.next ?: return "${board.favorite.compact}. ${emptyTitle(board.state).lowercase()}."
        val rest = board.later.take(2).joinToString(" and ") { TimeText.clock(it.atMs) }
        return "${board.favorite.compact}. Next train to ${next.label} in ${TimeText.countdown(next.atMs, nowMs)}, at ${TimeText.clock(next.atMs)}." +
            if (rest.isNotEmpty()) " Then $rest." else ""
    }

    fun render(context: Context, board: Board, page: Int, pages: Int, widthDp: Float, heightDp: Float, nowMs: Long, scale: Float): Bitmap {
        val w = widthDp.coerceAtLeast(60f)
        val h = heightDp.coerceAtLeast(40f)
        val bitmap = Bitmap.createBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        c.scale(scale, scale)
        val clip = Path().apply { addRoundRect(RectF(0f, 0f, w, h), RADIUS_DP, RADIUS_DP, Path.Direction.CW) }
        c.clipPath(clip)
        val face = display(context)
        when (layoutFor(w, h)) {
            Layout.STRIP -> strip(c, face, board, page, pages, w, h, nowMs)
            Layout.SQUARE -> square(c, face, board, page, pages, w, h, nowMs)
            Layout.CARD -> card(c, face, board, page, pages, w, h, nowMs, withThen = true)
            Layout.TALL -> tall(c, face, board, page, pages, w, h, nowMs)
        }
        return bitmap
    }

    // ------------------------------------------------------------------------------------------

    private fun card(c: Canvas, face: Typeface, board: Board, page: Int, pages: Int, w: Float, h: Float, now: Long, withThen: Boolean) {
        val next = board.next
        if (next == null) return empty(c, face, board, page, pages, w, h)
        val bg = color(next.colors, 0)
        val ink = inkOn(next.colors.firstOrNull())
        c.drawColor(bg)
        band(c, next.colors, w, h)

        // The giant number, bleeding off the right and bottom edges.
        val minutes = TimeText.minutes(next.atMs, now)
        val big = if (minutes == 0L) "NOW" else minutes.toString()
        val p = text(face, ink, 1f).apply { letterSpacing = -0.04f }
        val size = fitSize(p, big, maxSize = h * 1.35f, maxWidth = w * if (minutes == 0L) 0.5f else 0.52f)
        p.textSize = size
        val bigW = p.measureText(big)
        val capTop = h * 0.26f
        val baseline = capTop + size * 0.72f
        c.drawText(big, w - bigW + w * 0.035f, baseline, p)

        // Left column: where you are, where it goes.
        val left = 18f
        val colW = w - bigW - left - 4f
        val station = board.favorite.station.short.uppercase()
        val small = text(sansBold, ink, 11f).apply { letterSpacing = 0.18f }
        c.drawText(ellipsize(station, small, colW.coerceAtLeast(60f)), left, 26f, small)
        val dest = text(face, ink, 26f)
        val destText = "→ " + next.label.substringBefore(" via ").uppercase()
        dest.textSize = fitSize(dest, destText, maxSize = 26f, maxWidth = colW.coerceAtLeast(70f)).coerceAtLeast(15f)
        c.drawText(ellipsize(destText, dest, colW.coerceAtLeast(70f)), left, 26f + dest.textSize + 2f, dest)

        val meta = text(sansBold, ink, 10.5f).apply { letterSpacing = 0.15f; alpha = 190 }
        val metaText = (if (minutes == 0L) "LEAVING · " else "MIN · LEAVES ") + TimeText.clock(next.atMs)
        val thenLine = when {
            board.state == BoardState.STALE -> TimeText.updated(board.fetchedAtMs).uppercase()
            withThen && board.later.isNotEmpty() -> "THEN " + board.later.take(2).joinToString("  ·  ") { TimeText.clock(it.atMs) }
            else -> null
        }
        val bottom = h - 16f
        if (thenLine != null) {
            val then = text(sansBold, ink, 13.5f)
            c.drawText(ellipsize(thenLine, then, colW.coerceAtLeast(80f)), left, bottom, then)
            c.drawText(ellipsize(metaText, meta, colW.coerceAtLeast(80f)), left, bottom - 19f, meta)
        } else {
            c.drawText(ellipsize(metaText, meta, colW.coerceAtLeast(80f)), left, bottom, meta)
        }
        pageBars(c, ink, page, pages, w - 16f, 14f)
    }

    private fun square(c: Canvas, face: Typeface, board: Board, page: Int, pages: Int, w: Float, h: Float, now: Long) {
        val next = board.next
        if (next == null) return empty(c, face, board, page, pages, w, h)
        val ink = inkOn(next.colors.firstOrNull())
        c.drawColor(color(next.colors, 0))
        band(c, next.colors, w, h)

        val small = text(sansBold, ink, 10.5f).apply { letterSpacing = 0.16f }
        c.drawText(board.favorite.station.code, 14f, 22f, small)
        val dest = text(face, ink, 15f)
        c.drawText(ellipsize("→ " + next.label.substringBefore(" via ").uppercase(), dest, w - 28f), 14f, 40f, dest)

        val minutes = TimeText.minutes(next.atMs, now)
        val big = if (minutes == 0L) "NOW" else minutes.toString()
        val p = text(face, ink, 1f).apply { letterSpacing = -0.05f }
        p.textSize = fitSize(p, big, maxSize = h * 0.95f, maxWidth = w * 0.8f)
        val baseline = h + p.textSize * 0.08f
        c.drawText(big, -w * 0.02f, baseline, p)

        val meta = text(sansBold, ink, 11.5f).apply { textAlign = Paint.Align.RIGHT }
        c.drawText(if (minutes == 0L) "LEAVING" else "MIN", w - 14f, h - 30f, meta)
        c.drawText(TimeText.clock(next.atMs), w - 14f, h - 15f, meta)
        pageBars(c, ink, page, pages, w - 14f, 15f)
    }

    private fun strip(c: Canvas, face: Typeface, board: Board, page: Int, pages: Int, w: Float, h: Float, now: Long) {
        c.drawColor(NIGHT)
        val next = board.next
        val bar = 12f
        if (next != null) {
            val cols = next.colors.ifEmpty { listOf(0xFF8A8A8AL) }
            cols.take(2).forEachIndexed { i, col ->
                val seg = h / cols.take(2).size
                c.drawRect(0f, seg * i, bar, seg * (i + 1), fill(col.toInt()))
            }
        }
        val left = bar + 14f
        val wide = w >= 200f
        val numW: Float
        if (next != null) {
            val minutes = TimeText.minutes(next.atMs, now)
            val accent = next.colors.firstOrNull()?.toInt() ?: PAPER
            val big = if (minutes == 0L) "NOW" else minutes.toString()
            val p = text(face, accent, 1f).apply { letterSpacing = -0.03f }
            p.textSize = fitSize(p, big, maxSize = h * 0.78f, maxWidth = w * 0.34f)
            val unit = text(sansBold, PAPER, 11f).apply { letterSpacing = 0.1f }
            val unitText = if (minutes == 0L) "" else " MIN"
            val unitW = unit.measureText(unitText)
            val bigW = p.measureText(big)
            val right = w - 16f
            c.drawText(unitText, right - unitW, h / 2f + p.textSize * 0.36f, unit)
            c.drawText(big, right - unitW - bigW, h / 2f + p.textSize * 0.36f, p)
            numW = bigW + unitW + 24f
        } else numW = 16f

        val colW = w - left - numW
        val small = text(sansBold, PAPER, 10f).apply { letterSpacing = 0.16f; alpha = 175 }
        val title = text(face, PAPER, if (wide) 19f else 15f)
        if (next == null) {
            c.drawText(ellipsize(board.favorite.compact.uppercase(), small, colW), left, h / 2f - 6f, small)
            c.drawText(ellipsize(emptyTitle(board.state), title, colW), left, h / 2f + 14f, title)
            return
        }
        if (wide) {
            c.drawText(ellipsize(board.favorite.compact.uppercase() + "  ·  " + TimeText.clock(next.atMs), small, colW), left, h / 2f - 7f, small)
            c.drawText(ellipsize(next.label.uppercase(), title, colW), left, h / 2f + 15f, title)
        } else {
            c.drawText(board.favorite.station.code + " →", left, h / 2f - 4f, small)
            c.drawText(ellipsize(next.label.uppercase(), title, colW), left, h / 2f + 13f, title)
        }
    }

    private fun tall(c: Canvas, face: Typeface, board: Board, page: Int, pages: Int, w: Float, h: Float, now: Long) {
        c.drawColor(NIGHT)
        val top = (h * 0.58f).coerceAtMost(170f)
        c.save()
        c.clipRect(0f, 0f, w, top)
        card(c, face, board, page, pages, w, top, now, withThen = false)
        c.restore()
        val rows = board.later.take(4)
        val rowH = 30f
        var y = top + 6f
        val time = text(face, PAPER, 17f)
        val label = text(sansBold, PAPER, 11f).apply { letterSpacing = 0.12f; alpha = 190 }
        val mins = text(sansBold, PAPER, 12f).apply { textAlign = Paint.Align.RIGHT }
        for (d in rows) {
            if (y + rowH > h - 6f) break
            val col = d.colors.firstOrNull()?.toInt() ?: PAPER
            c.drawRect(16f, y + 6f, 22f, y + rowH - 6f, fill(col))
            val base = y + rowH / 2f + 6f
            c.drawText(TimeText.clock(d.atMs), 32f, base, time)
            val tW = time.measureText("00:00") + 44f
            c.drawText(ellipsize(d.label.uppercase(), label, w - tW - 80f), tW, base - 1f, label)
            c.drawText(TimeText.countdown(d.atMs, now).uppercase(), w - 16f, base - 1f, mins)
            y += rowH
        }
        if (rows.isEmpty()) {
            val hint = text(sansMedium, PAPER, 12f).apply { alpha = 170 }
            c.drawText("No later trains listed", 16f, top + 28f, hint)
        }
    }

    private fun empty(c: Canvas, face: Typeface, board: Board, page: Int, pages: Int, w: Float, h: Float) {
        c.drawColor(NIGHT)
        val small = text(sansBold, PAPER, 11f).apply { letterSpacing = 0.16f; alpha = 190 }
        c.drawText(ellipsize(board.favorite.compact.uppercase(), small, w - 40f), 16f, 26f, small)
        val title = emptyTitle(board.state)
        val p = text(face, PAPER, 1f)
        p.textSize = fitSize(p, title, maxSize = h * 0.32f, maxWidth = w - 32f)
        c.drawText(title, 16f, h * 0.5f + p.textSize * 0.36f, p)
        val hint = text(sansMedium, PAPER, 11.5f).apply { alpha = 170 }
        c.drawText(ellipsize(emptyHint(board.state), hint, w - 32f), 16f, h - 16f, hint)
        pageBars(c, PAPER, page, pages, w - 16f, 14f)
    }

    // ------------------------------------------------------------------------------------------

    /** A second line colour (a train running two routes) as a diagonal band across the card. */
    private fun band(c: Canvas, colors: List<Long>, w: Float, h: Float) {
        val second = colors.getOrNull(1) ?: return
        val path = Path().apply {
            moveTo(0f, h * 0.72f)
            lineTo(w, h * 0.30f)
            lineTo(w, h * 0.30f + h * 0.42f)
            lineTo(0f, h * 1.14f)
            close()
        }
        c.drawPath(path, fill(second.toInt()))
    }

    /** Small bars showing which favorite this is: a long bar for this one, dots for the rest. */
    private fun pageBars(c: Canvas, ink: Int, page: Int, pages: Int, right: Float, top: Float) {
        if (pages < 2) return
        val n = pages.coerceAtMost(6)
        var x = right
        for (i in n - 1 downTo 0) {
            val wBar = if (i == page) 14f else 5f
            val p = fill(ink).apply { alpha = if (i == page) 255 else 110 }
            c.drawRoundRect(RectF(x - wBar, top, x, top + 4f), 2f, 2f, p)
            x -= wBar + 3f
        }
    }

    private fun color(colors: List<Long>, i: Int): Int = colors.getOrNull(i)?.toInt() ?: NIGHT

    private fun inkOn(argb: Long?): Int = if (argb != null && Ink.wantsDarkText(argb)) NIGHT else Color.WHITE

    private fun text(face: Typeface, color: Int, sizeDp: Float) = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = face
        this.color = color
        textSize = sizeDp
        fontFeatureSettings = "tnum"
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }

    /** The largest size up to [maxSize] at which [s] fits in [maxWidth]. */
    private fun fitSize(p: Paint, s: String, maxSize: Float, maxWidth: Float): Float {
        val saved = p.textSize
        p.textSize = 100f
        val per100 = p.measureText(s).coerceAtLeast(1f)
        p.textSize = saved
        return minOf(maxSize, maxWidth / per100 * 100f)
    }

    private fun ellipsize(s: String, p: TextPaint, width: Float): String =
        TextUtils.ellipsize(s, p, width.coerceAtLeast(10f), TextUtils.TruncateAt.END).toString()

    fun emptyTitle(state: BoardState) = when (state) {
        BoardState.NO_DATA -> "LOADING"
        BoardState.STALE -> "NO SIGNAL"
        else -> "NO TRAINS"
    }

    fun emptyHint(state: BoardState) = when (state) {
        BoardState.NO_DATA -> "Getting times from PATH"
        BoardState.STALE -> "Tap to try again"
        else -> "Service may be suspended"
    }
}
