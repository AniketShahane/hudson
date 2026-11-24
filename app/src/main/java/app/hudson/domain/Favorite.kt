package app.hudson.domain

import app.hudson.core.Direction
import app.hudson.core.Network
import app.hudson.core.Station
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A saved board: trains leaving [stationCode], optionally only in one [directionLabel] and
 * only to some terminals. Codes rather than enums are stored so a renamed constant never
 * wipes the user's list.
 */
@Serializable
data class Favorite(
    val id: String,
    val stationCode: String,
    /** "ToNY" / "ToNJ", or null for both. */
    val directionLabel: String? = null,
    /** Terminal codes to keep; empty keeps every train. */
    val targets: List<String> = emptyList(),
) {
    val station: Station get() = Station.of(stationCode) ?: Station.EXCHANGE_PLACE
    val direction: Direction? get() = Direction.of(directionLabel)
    val targetStations: List<Station> get() = targets.mapNotNull { Station.of(it) }

    /** "Exchange Pl" */
    val title: String get() = station.short

    /** "to New York", "to WTC · 33rd St", or "all trains". */
    val subtitle: String
        get() {
            val t = targetStations
            return when {
                t.isNotEmpty() -> "to " + t.joinToString(" · ") { it.short }
                direction != null -> "to ${direction!!.title}"
                else -> "all trains"
            }
        }

    /** Compact form for tight spaces and the Live Update title: "Exchange Pl → NY". */
    val compact: String
        get() {
            val t = targetStations
            val to = when {
                t.size == 1 -> t.first().short
                direction != null -> direction!!.short
                else -> "all"
            }
            return "${station.short} → $to"
        }

    companion object {
        /** The two boards most Jersey City commuters want: Exchange Place to the city, WTC home. */
        val defaults: List<Favorite> = listOf(
            Favorite("exp-ny", Station.EXCHANGE_PLACE.code, Direction.TO_NY.feedLabel),
            Favorite("wtc-nj", Station.WTC.code, Direction.TO_NJ.feedLabel),
        )

        private val json = Json { ignoreUnknownKeys = true }
        private val listSerializer = ListSerializer(serializer())

        fun encode(list: List<Favorite>): String = json.encodeToString(listSerializer, list)

        fun decode(raw: String?): List<Favorite> =
            raw?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrNull() } ?: defaults

        /** A new favorite, with the direction dropped if the station only runs one way. */
        fun create(station: Station, direction: Direction?, targets: List<Station>, id: String): Favorite {
            val dirs = Network.directions(station)
            val dir = if (dirs.size == 1) dirs.first() else direction
            return Favorite(id, station.code, dir?.feedLabel, targets.filter { it != station }.map { it.code })
        }
    }
}

/** Cycles through favorites; an index from an older, longer list wraps instead of crashing. */
fun List<Favorite>.at(index: Int): Favorite = if (isEmpty()) Favorite.defaults.first() else this[Math.floorMod(index, size)]
