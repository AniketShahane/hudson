package app.hudson

import app.hudson.core.Direction
import app.hudson.core.LineColors
import app.hudson.core.Network
import app.hudson.core.Station
import app.hudson.data.feed.RidePathFeed
import app.hudson.domain.BoardState
import app.hudson.domain.Boards
import app.hudson.domain.Favorite
import app.hudson.domain.LivePlan
import app.hudson.domain.PosterLayout
import app.hudson.domain.RefreshPlan
import app.hudson.domain.TimeText
import app.hudson.domain.at
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A slice of the real feed's shape (Nov 2026), stamped 8:00:00 New York time. */
object Fixture {
    const val STAMP = "2026-11-24T08:00:00.000000-05:00"
    val stampMs: Long = OffsetDateTime.parse(STAMP).toInstant().toEpochMilli()
    val zone: ZoneId = ZoneId.of("America/New_York")

    val json = """
    {"results":[
      {"consideredStation":"EXP","destinations":[
        {"label":"ToNY","messages":[
          {"target":"WTC","secondsToArrival":"240","arrivalTimeMessage":"4 min","lineColor":"D93A30","headSign":"World Trade Center","lastUpdated":"$STAMP"},
          {"target":"WTC","secondsToArrival":"600","arrivalTimeMessage":"10 min","lineColor":"65C100","headSign":"World Trade Center","lastUpdated":"$STAMP"},
          {"target":"WTC","secondsToArrival":"601","arrivalTimeMessage":"10 min","lineColor":"65C100","headSign":"World Trade Center","lastUpdated":"$STAMP"},
          {"target":"WTC","secondsToArrival":"960","arrivalTimeMessage":"16 min","lineColor":"D93A30","headSign":"World Trade Center","lastUpdated":"$STAMP"}
        ]},
        {"label":"ToNJ","messages":[
          {"target":"NWK","secondsToArrival":"120","arrivalTimeMessage":"2 min","lineColor":"D93A30","headSign":"Newark","lastUpdated":"$STAMP"},
          {"target":"HOB","secondsToArrival":"420","arrivalTimeMessage":"7 min","lineColor":"65C100","headSign":"Hoboken","lastUpdated":"$STAMP"}
        ]}
      ]},
      {"consideredStation":"WTC","destinations":[
        {"label":"ToNJ","messages":[
          {"target":"NWK","secondsToArrival":"60","arrivalTimeMessage":"1 min","lineColor":"D93A30","headSign":"Newark","lastUpdated":"$STAMP"},
          {"target":"HOB","secondsToArrival":"300","arrivalTimeMessage":"5 min","lineColor":"65C100","headSign":"Hoboken","lastUpdated":"$STAMP"},
          {"target":"NWK","secondsToArrival":"bad","lineColor":"D93A30","headSign":"Newark","lastUpdated":"$STAMP"}
        ]}
      ]},
      {"consideredStation":"HOB","destinations":[
        {"label":"ToNJ","messages":[
          {"target":"JSQ","secondsToArrival":"180","lineColor":"4D92FB,FF9900","headSign":"Journal Square via Hoboken","lastUpdated":"$STAMP"}
        ]}
      ]},
      {"consideredStation":"ZZZ","destinations":[]}
    ]}
    """.trimIndent()
}

class FeedTest {
    private val feed = RidePathFeed.parse(Fixture.json, Fixture.stampMs + 5_000)

    @Test fun parsesEveryValidMessageAndSkipsBadOnes() {
        // 6 at EXP + 2 valid at WTC + 1 at HOB; the unknown station and the bad countdown are dropped.
        assertEquals(9, feed.arrivals.size)
    }

    @Test fun arrivalIsStampPlusCountdown() {
        val first = feed.arrivals.first { it.station == Station.WTC }
        assertEquals(Fixture.stampMs + 60_000, first.atMs)
        assertEquals(Direction.TO_NJ, first.direction)
        assertEquals(Station.NEWARK, first.target)
    }

    @Test fun twoLineTrainKeepsBothColours() {
        val hob = feed.arrivals.first { it.station == Station.HOBOKEN }
        assertEquals(listOf(0xFF4D92FBL, 0xFFFF9900L), hob.colors)
    }

    @Test fun missingStampFallsBackToFetchTime() {
        val body = """{"results":[{"consideredStation":"GRV","destinations":[{"label":"ToNY","messages":[{"target":"WTC","secondsToArrival":"30"}]}]}]}"""
        val f = RidePathFeed.parse(body, 1_000_000)
        assertEquals(1_030_000, f.arrivals.single().atMs)
        assertEquals("World Trade Center", f.arrivals.single().headsign)
    }

    @Test fun lineColoursIgnoreJunk() {
        assertEquals(emptyList(), LineColors.parse(null))
        assertEquals(listOf(0xFFD93A30L), LineColors.parse("#D93A30, nope"))
    }
}

class BoardTest {
    private val feed = RidePathFeed.parse(Fixture.json, Fixture.stampMs)
    private val now = Fixture.stampMs

    @Test fun exchangePlaceToNyShowsOnlyCityTrainsInOrder() {
        val board = Boards.build(Favorite.defaults[0], feed, now)
        assertEquals(BoardState.OK, board.state)
        assertTrue(board.departures.all { it.label == "WTC" })
        // The duplicate one second apart is folded into one train.
        assertEquals(listOf(240_000L, 600_000L, 960_000L), board.departures.map { it.atMs - now })
    }

    @Test fun wtcHomeboundShowsBothLines() {
        val board = Boards.build(Favorite.defaults[1], feed, now)
        assertEquals(listOf("Newark", "Hoboken"), board.departures.map { it.label })
    }

    @Test fun terminalFilterKeepsOnlyChosenTrains() {
        val fav = Favorite.create(Station.EXCHANGE_PLACE, Direction.TO_NJ, listOf(Station.HOBOKEN), "x")
        assertEquals(listOf("Hoboken"), Boards.build(fav, feed, now).departures.map { it.label })
        assertEquals("to Hoboken", fav.subtitle)
        assertEquals("Exchange Pl → Hoboken", fav.compact)
    }

    @Test fun viaTrainsSayTheirRoute() {
        val fav = Favorite.create(Station.HOBOKEN, Direction.TO_NJ, emptyList(), "h")
        assertEquals("Journal Sq via Hob", Boards.build(fav, feed, now).next?.label)
    }

    @Test fun departedTrainsDropOffAfterGrace() {
        val later = now + 4 * 60_000 + Boards.GRACE_MS + 1
        val board = Boards.build(Favorite.defaults[0], feed, later)
        assertEquals(600_000L, board.next!!.atMs - now)
    }

    @Test fun statesForNoDataEmptyAndStale() {
        assertEquals(BoardState.NO_DATA, Boards.build(Favorite.defaults[0], null, now).state)
        val fav = Favorite.create(Station.GROVE, Direction.TO_NY, emptyList(), "g")
        assertEquals(BoardState.EMPTY, Boards.build(fav, feed, now).state)
        assertEquals(BoardState.STALE, Boards.build(Favorite.defaults[0], feed, now + Boards.STALE_AFTER_MS + 1).state)
    }
}

class FavoriteTest {
    @Test fun oneWayStationsDropTheChosenDirection() {
        val wtc = Favorite.create(Station.WTC, Direction.TO_NY, emptyList(), "w")
        assertEquals(Direction.TO_NJ, wtc.direction)
        assertEquals(listOf(Direction.TO_NJ), Network.directions(Station.THIRTY_THIRD))
        assertEquals(Direction.entries, Network.directions(Station.HOBOKEN))
    }

    @Test fun roundTripsAndFallsBackToDefaults() {
        val list = Favorite.defaults + Favorite.create(Station.GROVE, null, emptyList(), "g")
        assertEquals(list, Favorite.decode(Favorite.encode(list)))
        assertEquals(Favorite.defaults, Favorite.decode(null))
        assertEquals(Favorite.defaults, Favorite.decode("{not json"))
        assertEquals("all trains", list[2].subtitle)
    }

    @Test fun cyclingWraps() {
        val list = Favorite.defaults
        assertEquals(list[0], list.at(2))
        assertEquals(list[1], list.at(-1))
        assertEquals(Favorite.defaults.first(), emptyList<Favorite>().at(3))
    }
}

class PlanTest {
    private val feed = RidePathFeed.parse(Fixture.json, Fixture.stampMs)
    private val now = Fixture.stampMs

    @Test fun refreshesJustAfterTheFirstTrainLeaves() {
        val boards = listOf(Boards.build(Favorite.defaults[0], feed, now), Boards.build(Favorite.defaults[1], feed, now))
        // WTC's 1-minute train leaves first: 60s + 20s.
        assertEquals(now + 80_000, RefreshPlan.nextAt(boards, now))
    }

    @Test fun refreshIsClampedBetweenAMinuteAndFifteen() {
        val empty = listOf(Boards.build(Favorite.defaults[0], null, now))
        assertEquals(now + RefreshPlan.MAX_GAP_MS, RefreshPlan.nextAt(empty, now))
        val leaving = listOf(Boards.build(Favorite.defaults[1], feed, now + 50_000))
        assertEquals(now + 50_000 + RefreshPlan.MIN_GAP_MS, RefreshPlan.nextAt(leaving, now + 50_000))
    }

    @Test fun liveCardCountsDownThreeTrains() {
        val card = LivePlan.card(Boards.build(Favorite.defaults[0], feed, now), now, now + 30 * 60_000)
        assertEquals("Exchange Pl → NY", card.title)
        assertEquals(3, card.metrics.size)
        assertNull(card.chipText)
        assertEquals(now + 240_000, card.chipWhenMs)
        val expected = "WTC · 4 min · then " + listOf(600_000L, 960_000L).joinToString(", ") { TimeText.clock(now + it) }
        assertEquals(expected, card.summary)
    }

    @Test fun chipSaysNowInTheLastTwoMinutes() {
        val card = LivePlan.card(Boards.build(Favorite.defaults[1], feed, now), now, now + 600_000)
        assertEquals("Now", card.chipText)
    }

    @Test fun sessionTicksUntilItEnds() {
        assertEquals(now + LivePlan.TICK_MS, LivePlan.nextTick(now, now + 10 * 60_000))
        assertNull(LivePlan.nextTick(now, now + 10_000))
        assertTrue(LivePlan.active(now + 1, now))
    }

    @Test fun clockAndCountdownText() {
        assertEquals("8:04", TimeText.clock(now + 240_000, Fixture.zone))
        assertEquals("Now", TimeText.countdown(now + 59_000, now))
        assertEquals("4 min", TimeText.countdown(now + 240_000, now))
        assertEquals("Updated 8:00", TimeText.updated(now, Fixture.zone))
    }
}

class PosterTest {
    @Test fun redrawsWhenTheMinuteDigitChanges() {
        val now = 1_000_000L
        val feed = app.hudson.data.feed.FeedSnapshot(
            listOf(app.hudson.data.feed.Arrival(Station.EXCHANGE_PLACE, Direction.TO_NY, Station.WTC, "World Trade Center", now + 252_000, listOf(0xFFD93A30L))),
            now,
        )
        val board = Boards.build(Favorite.defaults[0], feed, now)
        // 4:12 left: the "4" becomes "3" in 12 s.
        assertEquals(now + 12_000 + 250, app.hudson.domain.Redraw.nextAt(listOf(board), now))
        // Inside the last minute the poster says NOW; it is repainted when that train drops off the board.
        assertEquals(now + 252_000 + Boards.GRACE_MS + 250, app.hudson.domain.Redraw.nextAt(listOf(board), now + 200_000))
        // Nothing to count down: look again soon rather than never.
        assertEquals(now + app.hudson.domain.Redraw.IDLE_MS, app.hudson.domain.Redraw.nextAt(emptyList(), now))
        assertTrue(app.hudson.domain.Redraw.shouldFetch(null, now))
        assertTrue(app.hudson.domain.Redraw.shouldFetch(now - 60_000, now))
        assertTrue(!app.hudson.domain.Redraw.shouldFetch(now - 10_000, now))
    }

    @Test fun inkFlipsOnYellow() {
        assertTrue(app.hudson.domain.Ink.wantsDarkText(0xFFFF9900L))
        assertTrue(!app.hudson.domain.Ink.wantsDarkText(0xFFD93A30L))
        assertTrue(app.hudson.domain.Ink.wantsDarkText(0xFF65C100L))
        assertTrue(!app.hudson.domain.Ink.wantsDarkText(0xFF141414L))
    }

    @Test fun layoutFollowsTheRealSize() {
        val strip = PosterLayout.plan(553f, 51f, 3)
        assertEquals(PosterLayout.Kind.STRIP, strip.kind)
        assertEquals(PosterLayout.Kind.WIDE, PosterLayout.plan(300f, 150f, 3).kind)
        assertEquals(PosterLayout.Kind.WIDE, PosterLayout.plan(276f, 102f, 3).kind)
        assertEquals(PosterLayout.Kind.SQUARE, PosterLayout.plan(150f, 150f, 3).kind)
    }

    @Test fun openZoneIsAFingerSizedCornerNotTheWholeWidget() {
        for ((w, h) in listOf(130f to 51f, 553f to 51f, 110f to 110f, 262f to 190f, 384f to 400f)) {
            val z = PosterLayout.openZone(w, h)
            assertTrue(z.width >= 48f || z.width == w, "$w x $h wide enough")
            assertTrue(z.height >= 44f, "$w x $h tall enough")
            assertTrue(z.width <= w * 0.72f && z.height <= h, "$w x $h leaves room to switch")
        }
    }

    @Test fun rowsOnlyTakeWhatThePosterCanSpare() {
        // A 4x2 keeps the whole card for the poster.
        assertEquals(0, PosterLayout.plan(300f, 150f, 4).rows)
        // The widget in the bug report: one later train must not leave half the card empty.
        val reported = PosterLayout.plan(262f, 200f, 1)
        assertEquals(1, reported.rows)
        assertEquals(200f - PosterLayout.ROW_HEIGHT - PosterLayout.ROWS_PADDING, reported.posterHeight, 0.01f)
        // No later trains: no empty panel at any height.
        assertEquals(400f, PosterLayout.plan(300f, 400f, 0).posterHeight, 0.01f)
        // Never more rows than trains, nor than four.
        assertEquals(2, PosterLayout.plan(300f, 400f, 2).rows)
        assertEquals(4, PosterLayout.plan(300f, 400f, 9).rows)
        // Whatever the size, the poster keeps at least a strip's height and fills what the rows leave.
        for (w in 60..600 step 7) for (h in 40..500 step 7) for (n in 0..5) {
            val p = PosterLayout.plan(w.toFloat(), h.toFloat(), n)
            assertTrue(p.posterHeight > 39f && p.posterHeight <= h, "$w x $h")
            if (p.rows > 0) assertTrue(p.posterHeight >= 120f, "$w x $h")
        }
    }
}
