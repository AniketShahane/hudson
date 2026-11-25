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
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
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
        rule.mainClock.autoAdvance = false
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(MainActivity.NOTIFICATION_PERMISSION)

        step("01_home") {
            waitFor("TRACK ON LOCK SCREEN")
            waitFor("THEN")
            settle(1500)
            shot("01_home")
        }
        step("02_second_station") {
            rule.onAllNodesWithText("TO NEW JERSEY").onFirst().performSemanticsAction(SemanticsActions.OnClick)
            settle(1200)
            shot("02_wtc")
        }
        step("03_tracking") {
            tap("TRACK ON LOCK SCREEN")
            waitFor("ON LOCK SCREEN")
            settle(800)
            shot("03_tracking")
            tap("STOP")
            waitFor("TRACK ON LOCK SCREEN")
        }
        step("04_settings") {
            rule.onNodeWithContentDescription("Settings").performSemanticsAction(SemanticsActions.OnClick)
            settle(1200)
            waitFor("MY STATIONS")
            shot("04_settings")
        }
        step("05_add") {
            tap("ADD A STATION")
            waitFor("1 · LEAVING FROM")
            shot("05_add_empty")
            tap("GROVE ST")
            waitFor("SAVE GROVE ST")
            shot("06_add_grove")
            rule.onNodeWithText("SAVE GROVE ST", substring = true).performSemanticsAction(SemanticsActions.OnClick)
            settle(1500)
            waitFor("MY STATIONS")
            tap("Put it on the lock screen")
            shot("07_settings_three")
        }
        step("08_home_three") {
            back()
            settle(1200)
            waitFor("TRACK ON LOCK SCREEN")
            shot("08_home_three")
        }
        step("10_live_notification") {
            val card = runBlocking {
                val feed = Graph.feed.cached()
                val now = System.currentTimeMillis()
                LivePlan.card(Boards.build(Favorite.defaults[0], feed, now), now, now + 30 * 60_000)
            }
            LiveNotifier.post(context, card)
            val nm = context.getSystemService(NotificationManager::class.java)
            val posted = shadowOf(nm).allNotifications
            assertTrue("Live Update posted", posted.isNotEmpty())
            println("[walk] live title=${posted.first().extras.getCharSequence("android.title")} text=${posted.first().extras.getCharSequence("android.text")}")
        }

        step("11_phone_colors") {
            rule.onNodeWithContentDescription("Settings").performSemanticsAction(SemanticsActions.OnClick)
            settle(1200)
            waitFor("COLORS")
            tap("MATCH MY PHONE")
            settle(800)
            shot("11_settings_phone_colors")
            assertTrue("the choice is saved", runBlocking { Graph.settings.current().matchPhone })
            back()
            settle(1200)
            waitFor("TRACK ON LOCK SCREEN")
            shot("12_home_phone_colors")
        }

        if (failures.isNotEmpty()) throw AssertionError("Walkthrough steps failed:\n" + failures.joinToString("\n"))
    }
}
