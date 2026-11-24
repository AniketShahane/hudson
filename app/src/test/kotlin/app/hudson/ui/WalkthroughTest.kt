package app.hudson.ui

import android.app.NotificationManager
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.work.testing.WorkManagerTestInitHelper
import app.hudson.HudsonApp
import app.hudson.data.FeedSource
import app.hudson.data.Graph
import app.hudson.domain.Boards
import app.hudson.domain.Favorite
import app.hudson.domain.LivePlan
import app.hudson.notify.LiveNotifier
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.OffsetDateTime
import java.time.ZoneId

/** A feed shaped like the real one, stamped "now" so the countdowns are live. */
object SampleFeed : FeedSource {
    override suspend fun fetch(): String {
        val stamp = OffsetDateTime.now(ZoneId.of("America/New_York")).toString()
        fun m(target: String, secs: Int, color: String, sign: String) =
            """{"target":"$target","secondsToArrival":"$secs","arrivalTimeMessage":"","lineColor":"$color","headSign":"$sign","lastUpdated":"$stamp"}"""
        return """
        {"results":[
          {"consideredStation":"EXP","destinations":[
            {"label":"ToNY","messages":[${m("WTC", 252, "D93A30", "World Trade Center")},${m("WTC", 610, "65C100", "World Trade Center")},${m("WTC", 980, "D93A30", "World Trade Center")},${m("WTC", 1330, "65C100", "World Trade Center")}]},
            {"label":"ToNJ","messages":[${m("NWK", 130, "D93A30", "Newark")},${m("HOB", 420, "65C100", "Hoboken")}]}
          ]},
          {"consideredStation":"WTC","destinations":[
            {"label":"ToNJ","messages":[${m("NWK", 75, "D93A30", "Newark")},${m("HOB", 300, "65C100", "Hoboken")},${m("NWK", 540, "D93A30", "Newark")},${m("HOB", 900, "65C100", "Hoboken")}]}
          ]},
          {"consideredStation":"HOB","destinations":[
            {"label":"ToNJ","messages":[${m("JSQ", 200, "4D92FB,FF9900", "Journal Square via Hoboken")}]}
          ]}
        ]}
        """.trimIndent()
    }
}

class TestHudsonApp : HudsonApp() {
    override fun onCreate() {
        WorkManagerTestInitHelper.initializeTestWorkManager(this)
        Graph.init(this, SampleFeed)
        super.onCreate()
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-port-night-xhdpi", application = TestHudsonApp::class)
class WalkthroughTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val failures = mutableListOf<String>()

    private fun settle(ms: Long = 1200) {
        var left = ms
        while (left > 0) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
            rule.mainClock.advanceTimeBy(100)
            left -= 100
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun exists(text: String) = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(text: String, timeoutMs: Long = 30_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!exists(text)) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for \"$text\"" }
            settle(200)
        }
    }

    private fun tap(text: String) {
        rule.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    /** For labels that also appear behind a sheet: the sheet's copy is composed last. */
    private fun tapLast(text: String) {
        rule.onAllNodesWithText(text).onLast().performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    private fun scrollAction(node: SemanticsNode) = node.config.getOrNull(SemanticsActions.ScrollBy)?.action

    private fun scrollTo(text: String) {
        repeat(6) {
            val node = rule.onNodeWithText(text, substring = true).fetchSemanticsNode()
            val screen = rule.onAllNodes(isRoot()).fetchSemanticsNodes().first()
            val dy = node.positionInRoot.y - screen.size.height * 0.2f
            if (kotlin.math.abs(dy) < 30f) return
            var parent = node.parent
            while (parent != null && scrollAction(parent) == null) parent = parent.parent
            val action = checkNotNull(parent?.let(::scrollAction)) { "\"$text\" is not in a scrollable list" }
            rule.runOnUiThread { action(0f, dy) }
            settle(600)
        }
    }

    private fun shot(name: String) {
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
        println("[walk] shot $name")
    }

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
    }

    private fun step(name: String, block: () -> Unit) {
        try {
            block()
            println("[walk] $name ok")
        } catch (t: Throwable) {
            failures += "$name: ${t::class.java.simpleName}: ${t.message?.take(300)}"
            t.printStackTrace()
            runCatching { shot("FAILED_$name") }
        }
    }

    @Test
    fun walkthrough() {
        // The countdown badge turns forever, so the clock is driven by hand.
        rule.mainClock.autoAdvance = false

        step("01_home") {
            waitFor("Exchange Pl")
            waitFor("Leaves")
            settle(1500)
            shot("01_home")
            assertTrue("Exchange Place board shows WTC trains", exists("World Trade Center"))
        }
        step("02_second_favorite") {
            tap("WTC → NJ")
            waitFor("Newark")
            settle(800)
            shot("02_wtc_home")
        }
        step("03_live_more") {
            tap("More")
            shot("03_live_more")
        }
        step("04_favorites") {
            scrollTo("Favorites")
            shot("04_favorites")
        }
        step("05_editor") {
            scrollTo("Add a favorite")
            tap("Add a favorite")
            settle(1500)
            waitFor("New favorite")
            shot("05_editor")
            tapLast("Hoboken")
            tap("To NJ")
            shot("06_editor_hoboken")
            back()
        }
        step("07_lock_help") {
            scrollTo("Lock screen")
            tap("Lock screen")
            scrollTo("Lock screen")
            shot("07_widget_and_lock")
        }
        step("08_live_notification") {
            val context = RuntimeEnvironment.getApplication()
            shadowOf(context).grantPermissions(MainActivity.NOTIFICATION_PERMISSION)
            val card = runBlocking {
                val feed = Graph.feed.cached()
                val now = System.currentTimeMillis()
                LivePlan.card(Boards.build(Favorite.defaults[0], feed, now), now, now + 30 * 60_000)
            }
            LiveNotifier.post(context, card)
            val nm = context.getSystemService(NotificationManager::class.java)
            val posted = shadowOf(nm).allNotifications
            assertTrue("Live Update posted", posted.isNotEmpty())
            val n = posted.first()
            assertTrue("Live Update is ongoing", n.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
            println("[walk] live title=${n.extras.getCharSequence("android.title")} text=${n.extras.getCharSequence("android.text")} chip=${card.chipText} metrics=${card.metrics}")
        }

        if (failures.isNotEmpty()) throw AssertionError("Walkthrough steps failed:\n" + failures.joinToString("\n"))
    }
}
