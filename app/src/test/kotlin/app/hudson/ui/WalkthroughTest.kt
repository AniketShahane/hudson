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

/**
 * A feed shaped like the real one, with every station and line, stamped "now" so the countdowns
 * are live. Trains are spaced a few minutes apart on each line, as on a weekday.
 */
object SampleFeed : FeedSource {
    private data class Run(val line: app.hudson.core.Line, val color: String)

    override suspend fun fetch(): String {
        val stamp = OffsetDateTime.now(ZoneId.of("America/New_York")).toString()
        fun m(target: app.hudson.core.Station, secs: Int, color: String) =
            """{"target":"${target.code}","secondsToArrival":"$secs","arrivalTimeMessage":"","lineColor":"$color","headSign":"${target.title}","lastUpdated":"$stamp"}"""
        val runs = app.hudson.core.Line.entries.map { Run(it, java.lang.Long.toHexString(it.color).takeLast(6).uppercase()) }
        val stations = app.hudson.core.Station.entries.mapIndexed { n, station ->
            val groups = app.hudson.core.Network.directions(station).map { direction ->
                val toNy = direction == app.hudson.core.Direction.TO_NY
                val messages = runs.filter { station in it.line.stations }.flatMapIndexed { k, run ->
                    // Stations are listed west to east: the last is the New York end.
                    val terminal = if (toNy) run.line.stations.last() else run.line.stations.first()
                    if (terminal == station) emptyList()
                    else (0 until 3).map { i -> m(terminal, 70 + (n * 53 + k * 170) % 230 + i * (360 + k * 40) + (if (toNy) 0 else 95), run.color) }
                }
                """{"label":"${direction.feedLabel}","messages":[${messages.joinToString(",")}]}"""
            }
            """{"consideredStation":"${station.code}","destinations":[${groups.joinToString(",")}]}"""
        }
        return """{"results":[${stations.joinToString(",")}]}"""
    }
}

/**
 * PATH's alerts as they really were on 25–26 Nov 2026 (a storm advisory and a lift shutdown still
 * open), plus one made-up delay on the Newark line so a service alert is on screen too.
 */
object SampleAlerts : FeedSource {
    override suspend fun fetch(): String {
        val real = SampleAlerts::class.java.getResource("/alerts_sample.json")!!.readText().trim()
        val sent = System.currentTimeMillis() - 6 * 60_000
        val delay = """{"messageid":1,"sentdate2":"/Date($sent)/","Subject":"PATHAlert: Signal Problem","SentMessage":"NWK-WTC delayed. Crews are working on a signal problem at JSQ. JSQ-33, HOB-WTC, HOB-33 running.","TemplateName":"PATH - Signal Problem","state":"Open"}"""
        return "[" + delay + "," + real.removePrefix("[")
    }
}

class TestHudsonApp : HudsonApp() {
    override fun onCreate() {
        WorkManagerTestInitHelper.initializeTestWorkManager(this)
        Graph.init(this, SampleFeed, SampleAlerts)
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

    private fun shot(name: String, folder: String = "roborazzi") {
        java.io.File("build/outputs/$folder").mkdirs()
        captureScreenRoboImage("build/outputs/$folder/$name.png")
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

    private fun tapDescribed(description: String) {
        rule.onNode(SemanticsMatcher("described \"$description\"") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith(description) } == true
        }).performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    @Test
    fun walkthrough() {
        rule.mainClock.autoAdvance = false
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(MainActivity.NOTIFICATION_PERMISSION)

        step("01_my_trains") {
            waitFor("TRACK ON LOCK SCREEN")
            waitFor("THEN")
            waitFor("Signal Problem")
            settle(1500)
            shot("01_my_trains")
        }
        step("02_second_station") {
            rule.onAllNodesWithText("TO NEW JERSEY").onFirst().performSemanticsAction(SemanticsActions.OnClick)
            settle(1500)
            shot("02_my_trains_wtc")
        }
        step("03_tracking") {
            tap("TRACK ON LOCK SCREEN")
            waitFor("UNTIL")
            settle(800)
            shot("03_tracking")
            tap("STOP")
            waitFor("TRACK ON LOCK SCREEN")
        }
        step("04_stations_list") {
            tapDescribed("Stations")
            waitFor("NEW JERSEY")
            settle(1500)
            shot("04_stations_list")
            val described = rule.onAllNodes(isRoot()).fetchSemanticsNodes().flatMap { root ->
                fun all(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::all)
                all(root).flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            }
            assertTrue("every station is listed", app.hudson.core.Station.entries.all { st -> described.any { it.startsWith(st.title) } })
        }
        step("05_stations_map") {
            tapDescribed("MAP view")
            waitFor("MINUTES TO THE NEXT TRAIN")
            settle(2200)
            shot("05_stations_map")
            tapDescribed("Journal Square")
            waitFor("TO NEW YORK")
            settle(1200)
            shot("06_station_from_map")
            back()
            waitFor("MINUTES TO THE NEXT TRAIN")
            tapDescribed("LIST view")
            waitFor("NEW JERSEY")
        }
        step("07_station_board") {
            tapDescribed("Hoboken")
            waitFor("TO NEW JERSEY")
            settle(1500)
            shot("07_station_hoboken")
            assertTrue("the lift alert that names Hoboken shows on its board", exists("Elevators Severe Storm Shutdown"))
            // Android may put the app away and rebuild it at any moment: you must come back to the same page.
            rule.activityRule.scenario.recreate()
            settle(1500)
            waitFor("TO NEW JERSEY")
            assertTrue("still on Hoboken's board after the app is rebuilt", exists("HOBOKEN"))
            // Saving it from here puts it in My trains.
            rule.onAllNodesWithText("MY TRAINS").onFirst().performSemanticsAction(SemanticsActions.OnClick)
            settle(800)
            waitFor("IN MY TRAINS")
            shot("08_station_saved")
            assertTrue(runBlocking { Graph.settings.current().favorites.any { it.stationCode == "HOB" } })
            back()
            waitFor("NEW JERSEY")
        }
        step("09_alerts") {
            tapDescribed("Alerts")
            waitFor("EARLIER")
            settle(1500)
            shot("09_alerts")
            assertTrue(exists("Signal Problem") && exists("PATH Inclement Weather Advisory"))
            scrollTo("Track Condition")
            tap("Track Condition")
            settle(900)
            assertTrue("an ended alert opens to show what it said", exists("resuming normal service"))
            shot("10_alerts_earlier_open")
        }
        step("11_settings") {
            tapDescribed("My trains")
            waitFor("TRACK ON LOCK SCREEN")
            settle(1000)
            shot("11_my_trains_three")
            rule.onNodeWithContentDescription("Settings").performSemanticsAction(SemanticsActions.OnClick)
            settle(1200)
            waitFor("MY STATIONS")
            shot("12_settings")
        }
        step("13_add") {
            tap("ADD A STATION")
            waitFor("1 · LEAVING FROM")
            shot("13_add_empty")
            tap("GROVE ST")
            waitFor("SAVE GROVE ST")
            shot("14_add_grove")
            rule.onNodeWithText("SAVE GROVE ST", substring = true).performSemanticsAction(SemanticsActions.OnClick)
            settle(1500)
            waitFor("MY STATIONS")
        }
        step("15_phone_colors_and_about") {
            tap("MATCH MY PHONE")
            settle(800)
            assertTrue("the choice is saved", runBlocking { Graph.settings.current().matchPhone })
            scrollTo("ABOUT")
            settle(600)
            shot("15_settings_about_phone_colors")
            assertTrue(exists("not made by, affiliated with or endorsed by"))
            back()
            settle(1200)
            waitFor("TRACK ON LOCK SCREEN")
            shot("16_my_trains_phone_colors")
            tapDescribed("Stations")
            waitFor("NEW JERSEY")
            settle(1200)
            shot("17_stations_phone_colors")
            tapDescribed("My trains")
            waitFor("TRACK ON LOCK SCREEN")
        }
        step("18_live_notification") {
            val card = runBlocking {
                val feed = Graph.feed.cached()
                val now = System.currentTimeMillis()
                LivePlan.card(Boards.build(Favorite.defaults[0], feed, now), now, now + 30 * 60_000)
            }
            LiveNotifier.post(context, card)
            // The card's poster tile is drawn by the app; save it beside the screenshots to look at.
            app.hudson.notify.LiveTile.draw(context, card)?.let { tile ->
                java.io.FileOutputStream(java.io.File(java.io.File("build/outputs/roborazzi").apply { mkdirs() }, "live_tile.png")).use { tile.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            val nm = context.getSystemService(NotificationManager::class.java)
            val posted = shadowOf(nm).allNotifications
            assertTrue("Live Update posted", posted.isNotEmpty())
            println("[walk] live style=${posted.first().extras.getString("android.template")} progress=${posted.first().extras.getInt("android.progress", -1)}")
            assertTrue("the card is the progress style", posted.first().extras.getString("android.template").orEmpty().contains("ProgressStyle"))
            println("[walk] live title=${posted.first().extras.getCharSequence("android.title")} text=${posted.first().extras.getCharSequence("android.text")}")
        }

        if (failures.isNotEmpty()) throw AssertionError("Walkthrough steps failed:\n" + failures.joinToString("\n"))
    }

    /**
     * The five screens for the Google Play listing, on a small 9:16 phone (1440 x 2560): also the
     * check that nothing is cramped or cut off at the narrowest common width.
     */
    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h640dp-port-night-xxxhdpi", application = TestHudsonApp::class)
    fun storeScreenshots() {
        rule.mainClock.autoAdvance = false
        step("store") {
            waitFor("TRACK ON LOCK SCREEN")
            waitFor("Signal Problem")
            settle(1500)
            shot("phone_1_my_trains", "store")
            tapDescribed("Stations")
            waitFor("NEW JERSEY")
            settle(1500)
            shot("phone_2_stations", "store")
            tapDescribed("MAP view")
            waitFor("MINUTES TO THE NEXT TRAIN")
            settle(2200)
            shot("phone_3_map", "store")
            tapDescribed("Exchange Place")
            waitFor("TO NEW YORK")
            settle(1500)
            shot("phone_4_station", "store")
            back()
            waitFor("MINUTES TO THE NEXT TRAIN")
            tapDescribed("Alerts")
            waitFor("EARLIER")
            settle(1500)
            shot("phone_5_alerts", "store")
        }
        if (failures.isNotEmpty()) throw AssertionError("Store screenshots failed:\n" + failures.joinToString("\n"))
    }

    /** The same app in light mode, which nothing else here exercises. */
    @Test
    @Config(sdk = [36], qualifiers = "w412dp-h915dp-port-notnight-xhdpi", application = TestHudsonApp::class)
    fun lightMode() {
        rule.mainClock.autoAdvance = false
        step("light") {
            waitFor("TRACK ON LOCK SCREEN")
            waitFor("Signal Problem")
            settle(1500)
            shot("light_1_my_trains")
            tapDescribed("Stations")
            waitFor("NEW JERSEY")
            settle(1500)
            shot("light_2_stations")
            tapDescribed("MAP view")
            waitFor("MINUTES TO THE NEXT TRAIN")
            settle(2200)
            shot("light_3_map")
            tapDescribed("Alerts")
            waitFor("EARLIER")
            settle(1500)
            shot("light_4_alerts")
        }
        if (failures.isNotEmpty()) throw AssertionError("Light mode failed:\n" + failures.joinToString("\n"))
    }
}
