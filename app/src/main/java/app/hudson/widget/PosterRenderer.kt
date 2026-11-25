package app.hudson.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.res.ResourcesCompat
import app.hudson.R
import app.hudson.core.Station
import app.hudson.domain.Board
import app.hudson.domain.BoardState
import app.hudson.domain.Departure
import app.hudson.domain.Ink
import app.hudson.domain.PosterLayout
import app.hudson.domain.TimeText

/**
 * Draws the Swiss-poster widget as one picture: the next train's line colour fills the card and
 * its minutes are a giant number bleeding off the edge. A widget cannot lay type out like this
 * (no overflow, no custom fonts in text), so it is painted; [app.hudson.domain.Redraw] books a
 * repaint each time the number changes.
 *
 * It is painted at the widget's real size, never stretched, and [PosterLayout] picks the
 * composition. Text is never cut off: a line that does not fit shrinks, then falls back to a
 * shorter wording. Everything is laid out in dp; the canvas is scaled to pixels once.
 */
object PosterRenderer {
    private const val NIGHT = 0xFF141414.toInt()
    private const val PAPER = 0xFFF2EFE8.toInt()

    @Volatile private var display: Typeface? = null

    private fun display(context: Context): Typeface = display ?: (
        runCatching { ResourcesCompat.getFont(context, R.font.archivo_black) }.getOrNull() ?: Typeface.DEFAULT_BOLD
    ).also { display = it }

    private val sansBold: Typeface = Typeface.create(Typeface.SANS_SERIF, 700, false)
    private val sansMedium: Typeface = Typeface.create(Typeface.SANS_SERIF, 500, false)

    /** What a screen reader says for the picture. */
    fun describe(board: Board, nowMs: Long): String {
        val next = board.next ?: return "${board.favorite.compact}. ${emptyTitle(board.state).lowercase()}."
        val rest = board.later.take(2).joinToString(" and ") { TimeText.clock(it.atMs) }
        return "${board.favorite.compact}. Next train to ${next.label} in ${TimeText.countdown(next.atMs, nowMs)}, at ${TimeText.clock(next.atMs)}." +
            if (rest.isNotEmpty()) " Then $rest." else ""
    }

    /** The colour behind the picture, so a launcher that sizes the widget oddly never shows a gap. */
    fun ground(board: Board, widthDp: Float, heightDp: Float): Int {
        val next = board.next ?: return NIGHT
        return if (PosterLayout.plan(widthDp, heightDp, board.later.size).kind == PosterLayout.Kind.STRIP) NIGHT else color(next.colors, 0)
    }

    fun render(context: Context, board: Board, page: Int, pages: Int, widthDp: Float, heightDp: Float, nowMs: Long, scale: Float): Bitmap {
        val w = widthDp.coerceAtLeast(60f)
        val h = heightDp.coerceAtLeast(40f)
        val bitmap = Bitmap.createBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        c.scale(scale, scale)
        val face = display(context)
        val next = board.next
        val plan = PosterLayout.plan(w, h, board.later.size)
        when {
            plan.kind == PosterLayout.Kind.STRIP -> strip(c, face, board, w, h, nowMs)
            next == null -> empty(c, face, board, page, pages, w, h)
            else -> {
                c.save()
                c.clipRect(0f, 0f, w, plan.posterHeight)
                val then = plan.rows == 0
                if (plan.kind == PosterLayout.Kind.WIDE) wide(c, face, board, next, page, pages, w, plan.posterHeight, nowMs, then)
                else square(c, face, board, next, page, pages, w, plan.posterHeight, nowMs)
                c.restore()
                if (plan.rows > 0) rows(c, face, board.later.take(plan.rows), plan.posterHeight, w, h, nowMs)
            }
        }
        return bitmap
    }

    // ------------------------------------------------------------------------------------------

    private fun wide(c: Canvas, face: Typeface, board: Board, next: Departure, page: Int, pages: Int, w: Float, h: Float, now: Long, withThen: Boolean) {
        val ink = inkOn(next.colors.firstOrNull())
        c.drawColor(color(next.colors, 0))
        band(c, next.colors, w, h)
        val k = minOf(w / 300f, h / 150f).coerceIn(0.85f, 1.3f)
        val pad = 18f * k
        val bottom = h - 16f * k
        val barsW = pageBarsWidth(pages)

        val y1 = 26f * k
        val destSize = minOf(26f * k, h * 0.2f)
        val y2 = y1 + destSize * 0.72f + 9f * k

        val minutes = TimeText.minutes(next.atMs, now)
        val isNow = minutes == 0L
        val big = if (isNow) "NOW" else minutes.toString()
        val p = text(face, ink, 100f)
        // The word NOW stays whole inside the card, under the destination; a number is a poster
        // shape and bleeds off two edges.
        val glyph = if (isNow) {
            val b = bigFit(p, big, maxInkW = w * 0.5f, maxInkH = minOf(h * 0.42f, bottom - y2 - 9f * k))
            Glyph(w - pad - b.right, bottom, b)
        } else {
            val top = maxOf(h * 0.115f, if (pages > 1) 30f * k else 0f)
            val b = bigFit(p, big, maxInkW = w * if (big.length > 1) 0.54f else 0.5f, maxInkH = (h - top) / 0.9f)
            Glyph(w * 1.02f - b.right, h + 0.1f * -b.top, b)
        }
        c.drawText(big, glyph.x, glyph.baseline, p)

        // Left column. A line the big type does not come near may run the full width.
        val full = w - pad
        val beside = glyph.left - 14f * k
        val clear = 12f * k
        fun limit(top: Float, bottomY: Float, fullRight: Float = full) =
            (if (bottomY < glyph.top - clear || top > glyph.bottom + clear) fullRight else beside) - pad

        val small = text(sansBold, ink, 11f * k).apply { letterSpacing = 0.18f }
        fit(c, listOf(board.favorite.station.short.uppercase(), board.favorite.station.code), small, pad, y1, limit(y1 - 10f * k, y1, full - barsW - 6f), 9f)

        val dest = text(face, ink, destSize)
        fit(c, destOptions(next), dest, pad, y2, limit(y2 - destSize * 0.75f, y2), 12f)

        val leaves = TimeText.clock(next.atMs)
        val metaOptions = if (isNow) listOf("LEAVING · $leaves", "LEAVING $leaves", leaves) else listOf("MIN · LEAVES $leaves", "MIN · $leaves")
        val thenOptions = when {
            board.state == BoardState.STALE -> listOf(TimeText.updated(board.fetchedAtMs).uppercase())
            withThen && board.later.isNotEmpty() -> listOf(2, 1).map { n -> "THEN " + board.later.take(n).joinToString("  ·  ") { TimeText.clock(it.atMs) } }.distinct()
            else -> emptyList()
        }
        val meta = text(sansBold, ink, 10.5f * k).apply { letterSpacing = 0.15f; alpha = 200 }
        val bottomLimit = limit(bottom - 12f * k, bottom)
        when {
            thenOptions.isEmpty() -> fit(c, metaOptions, meta, pad, bottom, bottomLimit, 9f)
            // A low card has room for one line under the destination: say both on it.
            h < 124f -> fit(c, thenOptions.map { "${metaOptions.first()}  ·  $it" } + metaOptions, meta, pad, bottom, bottomLimit, 9f)
            else -> {
                val then = text(sansBold, ink, 13.5f * k)
                fit(c, thenOptions, then, pad, bottom, bottomLimit, 10.5f)
                val y = bottom - 19f * k
                fit(c, metaOptions, meta, pad, y, limit(y - 10f * k, y), 9f)
            }
        }
        pageBars(c, ink, page, pages, w - pad, 14f * k)
    }

    private fun square(c: Canvas, face: Typeface, board: Board, next: Departure, page: Int, pages: Int, w: Float, h: Float, now: Long) {
        val ink = inkOn(next.colors.firstOrNull())
        c.drawColor(color(next.colors, 0))
        band(c, next.colors, w, h)
        val k = (minOf(w, h) / 150f).coerceIn(0.8f, 1.3f)
        val pad = 14f * k
        val barsW = pageBarsWidth(pages)

        val y1 = 22f * k
        val small = text(sansBold, ink, 10.5f * k).apply { letterSpacing = 0.16f }
        fit(c, listOf(board.favorite.station.short.uppercase(), board.favorite.station.code), small, pad, y1, w - 2 * pad - barsW - 6f, 9f)
        val dest = text(face, ink, 16f * k)
        val y2 = y1 + 19f * k
        fit(c, destOptions(next), dest, pad, y2, w - 2 * pad, 11f)

        val minutes = TimeText.minutes(next.atMs, now)
        val leaves = TimeText.clock(next.atMs)
        val meta = text(sansBold, ink, 11.5f * k)
        if (minutes == 0L) {
            val y = h - 15f * k
            fit(c, listOf("LEAVING · $leaves", "LEAVING $leaves", leaves), meta, pad, y, w - 2 * pad, 9f)
            val p = text(face, ink, 100f)
            val b = bigFit(p, "NOW", maxInkW = w - 2 * pad, maxInkH = (y - 16f * k - y2 - 10f * k).coerceAtLeast(12f))
            c.drawText("NOW", pad - b.left, y - 18f * k, p)
        } else {
            meta.textAlign = Paint.Align.RIGHT
            c.drawText("MIN", w - pad, h - 30f * k, meta)
            c.drawText(leaves, w - pad, h - 15f * k, meta)
            val metaW = maxOf(meta.measureText("MIN"), meta.measureText(leaves))
            val big = minutes.toString()
            val p = text(face, ink, 100f)
            val top = y2 + 14f * k
            val b = bigFit(p, big, maxInkW = w - 2 * pad - metaW - 12f * k, maxInkH = (h - top) / 0.92f)
            c.drawText(big, pad - 1f - b.left, h + 0.08f * -b.top, p)
        }
        pageBars(c, ink, page, pages, w - pad, 15f * k - 2f)
    }

    /** Later trains on a dark panel under the poster. */
    private fun rows(c: Canvas, face: Typeface, trains: List<Departure>, top: Float, w: Float, h: Float, now: Long) {
        c.drawRect(0f, top, w, h, fill(NIGHT))
        val pad = if (w >= 200f) 18f else 14f
        val time = text(face, PAPER, 16f)
        val label = text(sansBold, PAPER, 11f).apply { letterSpacing = 0.12f; alpha = 190 }
        val mins = text(sansBold, PAPER, 11.5f).apply { textAlign = Paint.Align.RIGHT }
        val timeX = pad + 14f
        val labelX = timeX + time.measureText("12:00") + 12f
        var y = top + PosterLayout.ROWS_PADDING / 2f - 1f
        for (d in trains) {
            val base = y + PosterLayout.ROW_HEIGHT / 2f + 6f
            c.drawRoundRect(RectF(pad, y + 7f, pad + 5f, y + PosterLayout.ROW_HEIGHT - 7f), 2.5f, 2.5f, fill(d.colors.firstOrNull()?.toInt() ?: PAPER))
            c.drawText(TimeText.clock(d.atMs), timeX, base, time)
            val count = TimeText.countdown(d.atMs, now).uppercase()
            c.drawText(count, w - pad, base - 1f, mins)
            val room = w - pad - mins.measureText(count) - 10f - labelX
            if (room >= 44f) fit(c, listOf(d.label.uppercase(), d.label.substringBefore(" via ").uppercase()), label, labelX, base - 1f, room, 9f)
            y += PosterLayout.ROW_HEIGHT
        }
    }

    private fun strip(c: Canvas, face: Typeface, board: Board, w: Float, h: Float, now: Long) {
        c.drawColor(NIGHT)
        val next = board.next
        val k = (h / 70f).coerceIn(0.8f, 1.25f)
        val bar = if (w >= 200f) 12f else 9f
        if (next != null) {
            val cols = next.colors.ifEmpty { listOf(0xFF8A8A8AL) }.take(2)
            cols.forEachIndexed { i, col -> c.drawRect(0f, h / cols.size * i, bar, h / cols.size * (i + 1), fill(col.toInt())) }
        }
        val left = bar + 12f * k
        val right = w - 14f * k
        val mid = h / 2f
        val small = text(sansBold, PAPER, 10f * k).apply { letterSpacing = 0.16f; alpha = 180 }
        val title = text(face, PAPER, (if (w >= 200f) 19f else 15.5f) * k)
        if (next == null) {
            fit(c, listOf(board.favorite.compact.uppercase(), board.favorite.station.code), small, left, mid - 6f * k, right - left, 8.5f)
            fit(c, listOf(emptyTitle(board.state)), title, left, mid + 14f * k, right - left, 11f)
            return
        }
        val minutes = TimeText.minutes(next.atMs, now)
        val accent = next.colors.firstOrNull()?.toInt() ?: PAPER
        val big = if (minutes == 0L) "NOW" else minutes.toString()
        val unitText = if (minutes == 0L) "" else "MIN"
        val unit = text(sansBold, PAPER, 10.5f * k).apply { letterSpacing = 0.1f; textAlign = Paint.Align.RIGHT }
        val unitW = if (unitText.isEmpty()) 0f else unit.measureText(unitText) + 4f * k
        val p = text(face, accent, 100f)
        val b = bigFit(p, big, maxInkW = w * (if (minutes == 0L) 0.36f else 0.3f), maxInkH = h * 0.56f)
        val baseline = mid + -b.top / 2f
        c.drawText(unitText, right, baseline, unit)
        c.drawText(big, right - unitW - b.right, baseline, p)

        val colW = right - unitW - b.width() - 12f * k - left
        val dest = next.label.uppercase()
        val clock = TimeText.clock(next.atMs)
        fit(c, listOf(board.favorite.compact.uppercase() + "  ·  " + clock, board.favorite.station.code + " →  " + clock, board.favorite.station.code + " →"), small, left, mid - 6f * k, colW, 8.5f)
        fit(c, listOfNotNull(dest, dest.substringBefore(" VIA "), codeOf(next)), title, left, mid + 15f * k, colW, 11f)
    }

    private fun empty(c: Canvas, face: Typeface, board: Board, page: Int, pages: Int, w: Float, h: Float) {
        c.drawColor(NIGHT)
        val k = (minOf(w / 300f, h / 150f)).coerceIn(0.8f, 1.3f)
        val pad = 16f * k
        val small = text(sansBold, PAPER, 11f * k).apply { letterSpacing = 0.16f; alpha = 190 }
        fit(c, listOf(board.favorite.compact.uppercase(), board.favorite.station.code), small, pad, 26f * k, w - 2 * pad - pageBarsWidth(pages) - 6f, 9f)
        val title = emptyTitle(board.state)
        val p = text(face, PAPER, 100f)
        val b = bigFit(p, title, maxInkW = w - 2 * pad, maxInkH = h * 0.24f)
        c.drawText(title, pad - b.left, h * 0.5f + -b.top / 2f, p)
        val hint = text(sansMedium, PAPER, 11.5f * k).apply { alpha = 175 }
        fit(c, listOf(emptyHint(board.state), ""), hint, pad, h - 16f * k, w - 2 * pad, 10f)
        pageBars(c, PAPER, page, pages, w - pad, 14f * k)
    }

    // ------------------------------------------------------------------------------------------

    /** "→ JOURNAL SQ", then "→ JSQ" for where the name will not fit. */
    private fun destOptions(next: Departure): List<String> =
        listOfNotNull(next.label.substringBefore(" via ").uppercase(), codeOf(next)).map { "→ $it" }

    private fun codeOf(next: Departure): String? {
        val name = next.label.substringBefore(" via ")
        return Station.entries.firstOrNull { it.short.equals(name, ignoreCase = true) }?.code
    }

    /** Where a piece of big type was drawn, by the edges of its ink. */
    private class Glyph(val x: Float, val baseline: Float, b: Rect) {
        val left = x + b.left
        val top = baseline + b.top
        val bottom = baseline + b.bottom
    }

    /** Sets the largest size at which the ink of [s] is within the given width and height; returns its ink box. */
    private fun bigFit(p: Paint, s: String, maxInkW: Float, maxInkH: Float): Rect {
        val r = Rect()
        p.textSize = 200f
        p.getTextBounds(s, 0, s.length, r)
        p.textSize = 200f * minOf(maxInkW / r.width().coerceAtLeast(1), maxInkH / (-r.top).coerceAtLeast(1))
        p.getTextBounds(s, 0, s.length, r)
        return r
    }

    /**
     * Draws the first of [options] that fits in [width], shrinking the type as far as [minSize]
     * before moving to the next, shorter wording. Only if none fits is the last one cut short.
     */
    private fun fit(c: Canvas, options: List<String>, p: TextPaint, x: Float, y: Float, width: Float, minSize: Float) {
        if (width < 12f) return
        val size = p.textSize
        for (s in options) {
            val needed = p.measureText(s)
            val shrunk = if (needed <= width) size else size * width / needed
            if (shrunk >= minOf(minSize, size)) {
                p.textSize = shrunk * if (shrunk < size) 0.99f else 1f
                c.drawText(s, x, y, p)
                p.textSize = size
                return
            }
        }
        p.textSize = minOf(minSize, size)
        c.drawText(TextUtils.ellipsize(options.last(), p, width, TextUtils.TruncateAt.END).toString(), x, y, p)
        p.textSize = size
    }

    private fun pageBarsWidth(pages: Int): Float = if (pages < 2) 0f else 14f + (pages.coerceAtMost(6) - 1) * 8f

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
