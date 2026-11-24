package app.hudson.data.feed

import app.hudson.core.Direction
import app.hudson.core.LineColors
import app.hudson.core.Station
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.OffsetDateTime

/** One train due at a station, with its arrival as an absolute time so it never goes stale. */
data class Arrival(
    val station: Station,
    val direction: Direction,
    /** Terminal code the train is signed for ("WTC", "33S", ...), or null if the feed sent one we do not know. */
    val target: Station?,
    /** Destination as the platform sign shows it, e.g. "Journal Square via Hoboken". */
    val headsign: String,
    val atMs: Long,
    /** ARGB, one per line the train runs as. */
    val colors: List<Long>,
)

/** A parsed snapshot of the feed. */
data class FeedSnapshot(val arrivals: List<Arrival>, val fetchedAtMs: Long)

/**
 * Parser for https://www.panynj.gov/bin/portauthority/ridepath.json, the feed behind PATH's own
 * schedule pages. Shape (Nov 2026): results[] of { consideredStation, destinations[] of { label
 * "ToNY"/"ToNJ", messages[] of { target, secondsToArrival, arrivalTimeMessage, lineColor,
 * headSign, lastUpdated } } }. Every value is a string.
 */
object RidePathFeed {
    const val URL = "https://www.panynj.gov/bin/portauthority/ridepath.json"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    @Serializable
    private data class Root(val results: List<StationDto> = emptyList())

    @Serializable
    private data class StationDto(val consideredStation: String? = null, val destinations: List<DestinationDto> = emptyList())

    @Serializable
    private data class DestinationDto(val label: String? = null, val messages: List<MessageDto> = emptyList())

    @Serializable
    private data class MessageDto(
        val target: String? = null,
        val secondsToArrival: String? = null,
        val arrivalTimeMessage: String? = null,
        val lineColor: String? = null,
        val headSign: String? = null,
        val lastUpdated: String? = null,
    )

    /**
     * Turns the feed into arrivals. A message's countdown is relative to its own [lastUpdated]
     * stamp, so the arrival is that stamp plus the countdown; [fetchedAtMs] stands in when the
     * stamp is missing or unreadable.
     */
    fun parse(body: String, fetchedAtMs: Long): FeedSnapshot {
        val root = json.decodeFromString(Root.serializer(), body)
        val arrivals = buildList {
            for (s in root.results) {
                val station = Station.of(s.consideredStation) ?: continue
                for (d in s.destinations) {
                    val direction = Direction.of(d.label) ?: continue
                    for (m in d.messages) {
                        val seconds = m.secondsToArrival?.trim()?.toLongOrNull() ?: continue
                        val stamp = m.lastUpdated?.let { runCatching { OffsetDateTime.parse(it.trim()).toInstant().toEpochMilli() }.getOrNull() }
                            ?: fetchedAtMs
                        add(
                            Arrival(
                                station = station,
                                direction = direction,
                                target = Station.of(m.target),
                                headsign = m.headSign?.trim().orEmpty().ifEmpty { Station.of(m.target)?.title ?: "Train" },
                                atMs = stamp + seconds * 1000,
                                colors = LineColors.parse(m.lineColor),
                            ),
                        )
                    }
                }
            }
        }.sortedBy { it.atMs }
        return FeedSnapshot(arrivals, fetchedAtMs)
    }
}
