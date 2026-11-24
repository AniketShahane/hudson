package app.hudson.core

/** Which side of the Hudson a station is on. */
enum class Side { NJ, NY }

/**
 * The 13 PATH stations, keyed by the codes the Port Authority's realtime feed uses.
 * Order is geographic: Newark out to Hoboken, then WTC, then the 6th Avenue line uptown.
 */
enum class Station(val code: String, val title: String, val short: String, val side: Side) {
    NEWARK("NWK", "Newark", "Newark", Side.NJ),
    HARRISON("HAR", "Harrison", "Harrison", Side.NJ),
    JOURNAL_SQUARE("JSQ", "Journal Square", "Journal Sq", Side.NJ),
    GROVE("GRV", "Grove Street", "Grove St", Side.NJ),
    EXCHANGE_PLACE("EXP", "Exchange Place", "Exchange Pl", Side.NJ),
    NEWPORT("NEW", "Newport", "Newport", Side.NJ),
    HOBOKEN("HOB", "Hoboken", "Hoboken", Side.NJ),
    WTC("WTC", "World Trade Center", "WTC", Side.NY),
    CHRISTOPHER("CHR", "Christopher Street", "Christopher St", Side.NY),
    NINTH("09S", "9th Street", "9th St", Side.NY),
    FOURTEENTH("14S", "14th Street", "14th St", Side.NY),
    TWENTY_THIRD("23S", "23rd Street", "23rd St", Side.NY),
    THIRTY_THIRD("33S", "33rd Street", "33rd St", Side.NY);

    companion object {
        fun of(code: String?): Station? = entries.firstOrNull { it.code.equals(code?.trim(), ignoreCase = true) }
    }
}

/** The feed groups each station's trains by the side of the river they head for. */
enum class Direction(val feedLabel: String, val title: String, val short: String) {
    TO_NY("ToNY", "New York", "NY"),
    TO_NJ("ToNJ", "New Jersey", "NJ");

    companion object {
        fun of(label: String?): Direction? = entries.firstOrNull { it.feedLabel.equals(label?.trim(), ignoreCase = true) }
    }
}

/** Where trains from a station can head, so the editor only offers real choices. */
object Network {
    /** Terminals a train can be signed for. */
    val terminals: List<Station> = listOf(Station.NEWARK, Station.JOURNAL_SQUARE, Station.HOBOKEN, Station.WTC, Station.THIRTY_THIRD)

    /** Directions a station has trains in. Terminals at the river's edge only run one way. */
    fun directions(station: Station): List<Direction> = when (station) {
        Station.NEWARK -> listOf(Direction.TO_NY)
        Station.WTC, Station.THIRTY_THIRD -> listOf(Direction.TO_NJ)
        else -> Direction.entries
    }
}

/** Parses the feed's line colours, e.g. "D93A30" or "4D92FB,FF9900" (a train running two lines). */
object LineColors {
    fun parse(raw: String?): List<Long> = raw.orEmpty()
        .split(',')
        .mapNotNull { hex ->
            val clean = hex.trim().removePrefix("#")
            if (clean.length != 6) null else clean.toLongOrNull(16)?.let { 0xFF000000L or it }
        }
}
