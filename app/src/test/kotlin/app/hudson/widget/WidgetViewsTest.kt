package app.hudson.widget

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.SizeF
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.ViewFlipper
import app.hudson.R
import app.hudson.core.Station
import app.hudson.data.Settings
import app.hudson.data.feed.Arrival
import app.hudson.data.feed.FeedSnapshot
import app.hudson.core.Direction
import app.hudson.domain.Favorite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * The widget as the launcher builds it: the real layout inflated from the RemoteViews the app
 * sends, then the tap's page turn applied on top. Checks that a tap needs nothing but the turn.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xhdpi", application = Application::class)
class WidgetViewsTest {
    private val now = System.currentTimeMillis()
    private val feed = FeedSnapshot(
        listOf(
            Arrival(Station.EXCHANGE_PLACE, Direction.TO_NY, Station.WTC, "World Trade Center", now + 250_000, listOf(0xFFD93A30L)),
            Arrival(Station.EXCHANGE_PLACE, Direction.TO_NY, Station.WTC, "World Trade Center", now + 640_000, listOf(0xFFD93A30L)),
            Arrival(Station.WTC, Direction.TO_NJ, Station.HOBOKEN, "Hoboken", now + 130_000, listOf(0xFF65C100L)),
            Arrival(Station.WTC, Direction.TO_NJ, Station.NEWARK, "Newark", now + 500_000, listOf(0xFFD93A30L)),
        ),
        now,
    )

    private fun shot(view: View, name: String): Bitmap {
        val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(b))
        val dir = File("build/outputs/roborazzi").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return b
    }

    private fun inflate(views: RemoteViews, wDp: Int, hDp: Int): View {
        val context = RuntimeEnvironment.getApplication()
        val density = context.resources.displayMetrics.density
        val host = FrameLayout(context)
        val view = views.apply(context, host)
        val w = (wDp * density).toInt()
        val h = (hDp * density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        return view
    }

    /** The layout pass the launcher runs after any change. */
    private fun relayout(view: View) {
        // The launcher runs the crossfade over a few frames; here, jump to its end.
        view.findViewById<ViewFlipper>(R.id.flipper).let { f -> for (i in 0 until f.childCount) f.getChildAt(i).clearAnimation() }
        val w = view.width
        val h = view.height
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
    }

    private fun differ(a: Bitmap, b: Bitmap): Boolean {
        var changed = 0
        for (x in 0 until a.width step 7) for (y in 0 until a.height step 7) if (a.getPixel(x, y) != b.getPixel(x, y)) changed++
        return changed > 50
    }

    @Test
    fun aTapOnlyTurnsThePage() {
        val context = RuntimeEnvironment.getApplication()
        for (matchPhone in listOf(false, true)) {
            val tag = if (matchPhone) "phone" else "lines"
            val settings = Settings(favorites = Favorite.defaults, matchPhone = matchPhone)
            val view = inflate(Widgets.build(context, 1, SizeF(262f, 190f), settings, feed, index = 0, display = 0), 262, 190)
            val flipper = view.findViewById<ViewFlipper>(R.id.flipper)
            assertEquals("the station showing is page 0", 0, flipper.displayedChild)
            val before = shot(view, "widget_view_${tag}_1_showing")

            // What the tap sends: no pictures, only "show the other page".
            RemoteViews(context.packageName, R.layout.widget_poster).apply { setDisplayedChild(R.id.flipper, 1) }.reapply(context, view)
            relayout(view)
            assertEquals(1, flipper.displayedChild)
            val turned = shot(view, "widget_view_${tag}_2_after_tap")
            assertTrue("the next station was already there to show", differ(before, turned))

            // The repaint that follows: the new station on page 0 again, the one after it preloaded.
            // Tidying up: first the station is brought back to page 0 (page 1 untouched)...
            Widgets.build(context, 1, SizeF(262f, 190f), settings, feed, index = 1, current = 0, withNext = false, display = 0).reapply(context, view)
            relayout(view)
            assertEquals(0, flipper.displayedChild)
            val settled = shot(view, "widget_view_${tag}_3_settled")
            assertFalse("settling changes nothing you can see", differ(turned, settled))
            // ...then the station after it is drawn on page 1, with no page turn sent.
            Widgets.build(context, 1, SizeF(262f, 190f), settings, feed, index = 1).reapply(context, view)
            relayout(view)
            assertEquals(0, flipper.displayedChild)
            assertFalse("nor does preloading the next one", differ(settled, shot(view, "widget_view_${tag}_4_rest")))
            assertTrue(view.findViewById<View>(R.id.tap).hasOnClickListeners())
            assertTrue(view.findViewById<View>(R.id.open).hasOnClickListeners())
        }
    }

    @Test
    fun oneFavoriteOpensTheApp() {
        val context = RuntimeEnvironment.getApplication()
        val settings = Settings(favorites = Favorite.defaults.take(1))
        val view = inflate(Widgets.build(context, 2, SizeF(300f, 150f), settings, feed, index = 0), 300, 150)
        assertEquals(View.GONE, view.findViewById<View>(R.id.open).visibility)
        assertTrue(view.findViewById<View>(R.id.tap).hasOnClickListeners())
        shot(view, "widget_view_single")
    }
}
