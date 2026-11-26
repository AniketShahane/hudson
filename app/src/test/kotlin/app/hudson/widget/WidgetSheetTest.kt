package app.hudson.widget

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import app.hudson.core.Station
import app.hudson.domain.Board
import app.hudson.domain.BoardState
import app.hudson.domain.Departure
import app.hudson.domain.Favorite
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * A widget on a launcher cannot be photographed in a test, so this paints the same picture the
 * phone would, at the sizes launchers really hand out, and lays them on labelled sheets beside
 * the app screenshots. The sheets are this test's own drawing; the widgets on them are real.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class)
class WidgetSheetTest {
    private val now = 1_795_000_000_000L
    private val red = listOf(0xFFD93A30L)
    private val green = listOf(0xFF65C100L)
    private val blueOrange = listOf(0xFF4D92FBL, 0xFFFF9900L)
    private val yellow = listOf(0xFFFF9900L)

    private fun train(label: String, inSec: Int, colors: List<Long>) = Departure(label, label, now + inSec * 1000L, colors)

    private fun board(fav: Favorite, vararg trains: Departure, state: BoardState = BoardState.OK) =
        Board(fav, trains.toList(), state, now - 30_000)

    private val exp = Favorite("e", Station.EXCHANGE_PLACE.code, "ToNY")
    private val wtc = Favorite("w", Station.WTC.code, "ToNJ")
    private val chr = Favorite("c", Station.CHRISTOPHER.code, "ToNJ")

    /** Name to board: the states worth seeing at every size. */
    private val states = listOf(
        "4min" to board(exp, train("WTC", 250, red), train("WTC", 610, red), train("33rd St", 1150, yellow), train("WTC", 1700, red), train("WTC", 2300, red)),
        "now_one_later" to board(exp, train("WTC", 20, red), train("WTC", 320, red)),
        "12min_two_lines_long_names" to board(chr, train("Journal Sq via Hob", 750, blueOrange), train("Hoboken", 1100, listOf(0xFF4D92FBL)), train("Journal Sq via Hob", 1900, blueOrange)),
        "now_long_name_alone" to board(chr, train("Journal Sq via Hob", 10, blueOrange)),
        "green_2min" to board(wtc, train("Hoboken", 130, green), train("Newark", 400, red), train("Hoboken", 900, green)),
        "yellow_dark_ink_stale" to board(exp, train("33rd St", 480, yellow), train("33rd St", 1000, yellow), state = BoardState.STALE),
        "no_trains" to board(exp, state = BoardState.EMPTY),
    )

    /** Width by height in dp. Pixel cells are (73n-16) by (118m-16) upright and (142n-15) by (66m-15) sideways; One UI's are wider and lower. */
    private val sizes = listOf(
        130 to 51, 269 to 51, 553 to 51, 160 to 72, 330 to 72, 250 to 90,
        130 to 102, 203 to 102, 276 to 102, 349 to 102, 553 to 117, 269 to 117,
        110 to 110, 150 to 150, 160 to 190, 200 to 200, 130 to 220,
        220 to 150, 262 to 190, 262 to 200, 300 to 150, 330 to 190, 276 to 220, 349 to 220, 400 to 200,
        150 to 260, 276 to 338, 330 to 290, 384 to 400,
    )

    @Test
    fun sheets() {
        val context = RuntimeEnvironment.getApplication()
        val scale = 2f
        val gap = 24f
        val sheetW = 1180f
        // The line-colour poster for every state; the phone-colour look, light and dark, for the main ones.
        val phoneStates = setOf("4min", "now_one_later", "12min_two_lines_long_names", "no_trains")
        val looks = listOf<Pair<String, Boolean?>>("" to null, "_phone_light" to false, "_phone_dark" to true)
        for ((stateName, board) in states) for ((look, night) in looks) {
            if (night != null && stateName !in phoneStates) continue
            // The phone's colours as the launcher would resolve them in light or dark mode.
            val themed = night?.let {
                val config = android.content.res.Configuration(context.resources.configuration)
                config.uiMode = (config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (it) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO
                context.createConfigurationContext(config)
            }
            val name = stateName + look
            // Flow the widgets left to right, wrapping.
            var x = gap
            var y = gap
            var rowH = 0f
            val places = sizes.map { (w, h) ->
                if (x + w + gap > sheetW) { x = gap; y += rowH + gap + 14f; rowH = 0f }
                val at = x to y
                x += w + gap
                rowH = maxOf(rowH, h.toFloat())
                at
            }
            val sheetH = y + rowH + gap + 14f
            val sheet = Bitmap.createBitmap((sheetW * scale).toInt(), (sheetH * scale).toInt(), Bitmap.Config.ARGB_8888)
            val c = Canvas(sheet)
            c.scale(scale, scale)
            c.drawColor(if (look == "_phone_light") 0xFFB9C4D6.toInt() else 0xFF2B2440.toInt())
            val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (look == "_phone_light") Color.BLACK else Color.WHITE; alpha = 170; textSize = 10f }
            sizes.forEachIndexed { i, (w, h) ->
                val (px, py) = places[i]
                val bmp = if (themed == null) PosterRenderer.render(context, board, 0, 2, w.toFloat(), h.toFloat(), now, scale)
                else {
                    // Put together the way the launcher does: the ground colour, then each stencil tinted with its colour.
                    val out = Bitmap.createBitmap((w * scale).toInt(), (h * scale).toInt(), Bitmap.Config.ARGB_8888)
                    val oc = Canvas(out)
                    oc.drawColor(themed.getColor(PosterRenderer.groundRes(board, w.toFloat(), h.toFloat())))
                    val layers = PosterRenderer.renderLayers(context, board, 0, 2, w.toFloat(), h.toFloat(), now, scale)
                    for (painted in layers) {
                        val p = Paint(Paint.FILTER_BITMAP_FLAG)
                        painted.layer.tint?.let { p.colorFilter = android.graphics.PorterDuffColorFilter(themed.getColor(it), android.graphics.PorterDuff.Mode.SRC_IN) }
                        assertEquals("stencils cost one byte a pixel", painted.layer.tint != null, painted.bitmap.config == Bitmap.Config.ALPHA_8)
                        oc.drawBitmap(painted.bitmap, null, RectF(0f, 0f, out.width.toFloat(), out.height.toFloat()), p)
                    }
                    out
                }
                assertEquals("painted at the size asked for", (w * scale).toInt(), bmp.width)
                c.save()
                c.translate(px, py)
                // The launcher rounds the corners; drawn here so a label hidden by one shows.
                c.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 24f, 24f, Path.Direction.CW) })
                c.drawBitmap(bmp, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
                c.restore()
                c.drawText("$w × $h", px, py + h + 11f, caption)
            }
            val dir = File("build/outputs/roborazzi").apply { mkdirs() }
            FileOutputStream(File(dir, "widget_sheet_$name.png")).use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("[sheet] $name: ${PosterRenderer.describe(board, now)}")
        }
    }

    /** The launcher icon as the launcher shows it (squircle and circle, colour and themed), and the status-bar mark. */
    @Test
    fun icons() {
        val context = RuntimeEnvironment.getApplication()
        val cell = 320
        val sheet = Bitmap.createBitmap(cell * 4 + 20, cell + 120, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawColor(0xFF2B2440.toInt())
        fun layer(id: Int, tint: Int? = null): Bitmap {
            val b = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
            val d = context.getDrawable(id)!!.mutate()
            tint?.let { d.setTint(it) }
            d.setBounds(0, 0, 432, 432)
            d.draw(Canvas(b))
            return b
        }
        fun masked(layers: List<Bitmap>, circle: Boolean): Bitmap {
            val out = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
            val oc = Canvas(out)
            // The launcher shows the middle 72 of the icon's 108 dp.
            val clip = Path().apply { if (circle) addCircle(216f, 216f, 144f, Path.Direction.CW) else addRoundRect(RectF(72f, 72f, 360f, 360f), 96f, 96f, Path.Direction.CW) }
            oc.clipPath(clip)
            layers.forEach { oc.drawBitmap(it, 0f, 0f, null) }
            return out
        }
        val colour = listOf(layer(app.hudson.R.drawable.ic_launcher_background), layer(app.hudson.R.drawable.ic_launcher_foreground))
        val themedGround = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF2E4578.toInt()) }
        val themed = listOf(themedGround, layer(app.hudson.R.drawable.ic_launcher_monochrome, 0xFFD9E2FF.toInt()))
        listOf(masked(colour, false), masked(colour, true), masked(themed, false), masked(themed, true)).forEachIndexed { i, b ->
            c.drawBitmap(b, android.graphics.Rect(56, 56, 376, 376), RectF(10f + i * cell, 10f, 10f + i * cell + cell - 10f, cell.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        // The status-bar mark.
        listOf(app.hudson.R.drawable.ic_stat_train to 100).fold(20f) { x, (id, w) ->
            val d = context.getDrawable(id)!!.mutate()
            d.setTint(0xFFD93A30.toInt())
            d.setBounds(x.toInt(), cell + 10, x.toInt() + w, cell + 110)
            d.draw(c)
            x + w + 30
        }
        val dir = File("build/outputs/roborazzi").apply { mkdirs() }
        FileOutputStream(File(dir, "icons.png")).use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * A mock of the lock-screen card. Android draws the real one and its exact layout cannot be
     * rendered here; this is this test's own arrangement of the real tile, train and station
     * pictures over a track of ten dashes, to judge them together. Progress shown: 6 of 10 minutes.
     */
    @Test
    fun liveCardMock() {
        val context = RuntimeEnvironment.getApplication()
        val cases = listOf(Triple("WTC", red, false), Triple("JSQ", blueOrange, false), Triple("HOB", green, true))
        val cardW = 760f
        val cardH = 300f
        val sheet = Bitmap.createBitmap((cardW + 40).toInt(), ((cardH + 20) * cases.size + 20).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawColor(0xFF2B2440.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        cases.forEachIndexed { n, (code, colors, light) ->
            val top = 20f + n * (cardH + 20)
            p.color = if (light) 0xFFF4F3F7.toInt() else 0xFF1E1E22.toInt()
            c.drawRoundRect(RectF(20f, top, 20f + cardW, top + cardH), 44f, 44f, p)
            val ink = if (light) 0xFF141414.toInt() else Color.WHITE
            val card = app.hudson.domain.LiveCard(
                title = "Exchange Pl → NY", metrics = emptyList(), chipText = null, chipWhenMs = null, summary = "", endsAtMs = 0,
                approach = app.hudson.domain.Approach(360, 600, colors), tile = code,
            )
            p.color = ink; p.textSize = 34f; p.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, 700, false)
            c.drawText("→ $code", 50f, top + 70f, p)
            val after = 50f + p.measureText("→ $code") + 22f
            p.textSize = 24f; p.typeface = android.graphics.Typeface.SANS_SERIF; p.alpha = 200
            c.drawText("03:27", after, top + 70f, p)
            p.alpha = 255
            p.textSize = 28f; p.typeface = android.graphics.Typeface.SANS_SERIF; p.alpha = 190
            c.drawText("8:46 from Exchange Pl · then 8:52, 9:01", 50f, top + 112f, p)
            p.alpha = 255
            c.drawBitmap(app.hudson.notify.LiveTile.draw(context, card)!!, null, RectF(cardW - 110f, top + 30f, cardW - 10f, top + 130f), Paint(Paint.FILTER_BITMAP_FLAG))
            // The track: ten dashes, six travelled, the rest dimmed as the system does.
            val x0 = 50f; val x1 = cardW - 70f; val y = top + 210f; val gap = 8f
            val dash = (x1 - x0 - gap * 9) / 10f
            repeat(10) { i ->
                p.color = colors[i % colors.size].toInt(); p.alpha = if (i < 6) 255 else 90
                c.drawRoundRect(RectF(x0 + i * (dash + gap), y - 9f, x0 + i * (dash + gap) + dash, y + 9f), 9f, 9f, p)
            }
            p.alpha = 255
            val tx = x0 + 6 * (dash + gap) - gap / 2f
            c.drawBitmap(app.hudson.notify.LiveTile.train(context, colors), null, RectF(tx - 60f, y - 30f, tx + 60f, y + 30f), Paint(Paint.FILTER_BITMAP_FLAG))
            c.drawBitmap(app.hudson.notify.LiveTile.station(context, colors.last()), null, RectF(x1 + 14f, y - 22f, x1 + 58f, y + 22f), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val dir = File("build/outputs/roborazzi").apply { mkdirs() }
        FileOutputStream(File(dir, "live_card_mock.png")).use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The Google Play listing's pictures: the 512 px icon and the 1024 x 500 feature graphic. */
    @Test
    fun storeGraphics() {
        val context = RuntimeEnvironment.getApplication()
        val dir = File("build/outputs/store").apply { mkdirs() }
        fun layer(id: Int, size: Int): Bitmap {
            val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            context.getDrawable(id)!!.apply { setBounds(0, 0, size, size) }.draw(Canvas(b))
            return b
        }
        // Play wants the full square and rounds it itself: the middle 72 of the icon's 108 dp, at 512 px.
        val full = 768
        val icon = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        Canvas(icon).apply {
            val inset = -(full - 512) / 2f
            drawBitmap(layer(app.hudson.R.drawable.ic_launcher_background, full), inset, inset, null)
            drawBitmap(layer(app.hudson.R.drawable.ic_launcher_foreground, full), inset, inset, null)
        }
        FileOutputStream(File(dir, "icon_512.png")).use { icon.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val feature = Bitmap.createBitmap(1024, 500, Bitmap.Config.ARGB_8888)
        Canvas(feature).apply {
            drawColor(0xFFD93A30.toInt())
            // The tunnel and its train, large, running off the right edge.
            val art = 900
            drawBitmap(layer(app.hudson.R.drawable.ic_launcher_foreground, art), 1024f - art * 0.60f, 500f - art * 0.80f, null)
            val face = androidx.core.content.res.ResourcesCompat.getFont(context, app.hudson.R.font.archivo_black)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = face; textSize = 112f; letterSpacing = -0.02f }
            drawText("HUDSON", 64f, 222f, p)
            p.textSize = 40f; p.letterSpacing = 0f
            drawText("PATH TRAIN TIMES", 68f, 296f, p)
            drawText("AT A GLANCE", 68f, 344f, p)
            p.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, 700, false); p.textSize = 22f; p.alpha = 215; p.letterSpacing = 0.14f
            drawText("AN UNOFFICIAL APP", 70f, 430f, p)
        }
        FileOutputStream(File(dir, "feature_1024x500.png")).use { feature.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertEquals(512, icon.width)
    }
}
