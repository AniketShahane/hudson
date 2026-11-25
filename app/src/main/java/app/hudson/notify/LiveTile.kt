package app.hudson.notify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import app.hudson.R
import app.hudson.domain.Ink
import app.hudson.domain.LiveCard

/**
 * The card's picture: a small poster in the train's line colour with where it is going, in the
 * same heavy type as the widget. A train on two lines gets the second colour as a diagonal band.
 * It shows nothing that changes with time, so it never needs redrawing to stay true.
 */
object LiveTile {
    private const val SIZE = 192

    fun draw(context: Context, card: LiveCard): Bitmap? {
        val colors = card.approach?.colors.orEmpty()
        val text = card.tile ?: return null
        val first = colors.firstOrNull() ?: return null
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        val s = SIZE.toFloat()
        c.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, s, s), s * 0.24f, s * 0.24f, Path.Direction.CW) })
        c.drawColor(first.toInt())
        colors.getOrNull(1)?.let { second ->
            val band = Path().apply { moveTo(0f, s * 0.78f); lineTo(s, s * 0.36f); lineTo(s, s * 0.80f); lineTo(0f, s * 1.22f); close() }
            c.drawPath(band, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = second.toInt() })
        }
        val ink = if (Ink.wantsDarkText(first)) 0xFF141414.toInt() else Color.WHITE
        val face = runCatching { ResourcesCompat.getFont(context, R.font.archivo_black) }.getOrNull() ?: Typeface.DEFAULT_BOLD
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; color = ink; textAlign = Paint.Align.CENTER }
        // An arrow above, the destination below, both as large as the tile allows.
        p.textSize = s * 0.34f
        c.drawText("\u2192", s / 2f, s * 0.42f, p)
        p.textSize = 100f
        p.textSize = minOf(s * 0.40f, 100f * (s * 0.80f) / p.measureText(text).coerceAtLeast(1f))
        c.drawText(text, s / 2f, s * 0.80f, p)
        return bitmap
    }
}
