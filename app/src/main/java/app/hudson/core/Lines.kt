package app.hudson.core

/**
 * The four PATH routes, in the colours the Port Authority gives them. All four run every day
 * (since May 2026); late at night Journal Square–33rd Street runs via Hoboken instead.
 * [code] is how PATH's own alerts name a route ("NWK-WTC delayed").
 */
enum class Line(val code: String, val title: String, val color: Long, val stations: List<Station>) {
    NWK_WTC(
        "NWK-WTC", "Newark – World Trade Center", 0xFFD93A30,
        listOf(Station.NEWARK, Station.HARRISON, Station.JOURNAL_SQUARE, Station.GROVE, Station.EXCHANGE_PLACE, Station.WTC),
    ),
    HOB_WTC(
        "HOB-WTC", "Hoboken – World Trade Center", 0xFF65C100,
        listOf(Station.HOBOKEN, Station.NEWPORT, Station.EXCHANGE_PLACE, Station.WTC),
    ),
    JSQ_33(
        "JSQ-33", "Journal Square – 33rd Street", 0xFFFF9900,
        listOf(Station.JOURNAL_SQUARE, Station.GROVE, Station.NEWPORT, Station.CHRISTOPHER, Station.NINTH, Station.FOURTEENTH, Station.TWENTY_THIRD, Station.THIRTY_THIRD),
    ),
    HOB_33(
        "HOB-33", "Hoboken – 33rd Street", 0xFF4D92FB,
        listOf(Station.HOBOKEN, Station.CHRISTOPHER, Station.NINTH, Station.FOURTEENTH, Station.TWENTY_THIRD, Station.THIRTY_THIRD),
    );

    companion object {
        fun serving(station: Station): List<Line> = entries.filter { station in it.stations }
    }
}

/** Where the stations are, for "nearest station". Degrees, from the parent stations in PATH's published GTFS stops.txt. */
object Geo {
    private val at: Map<Station, Pair<Double, Double>> = mapOf(
        Station.NEWARK to (40.73454 to -74.16375),
        Station.HARRISON to (40.73942 to -74.15587),
        Station.JOURNAL_SQUARE to (40.73301 to -74.06289),
        Station.GROVE to (40.71966 to -74.04245),
        Station.EXCHANGE_PLACE to (40.71676 to -74.03238),
        Station.NEWPORT to (40.72699 to -74.03383),
        Station.HOBOKEN to (40.73586 to -74.02922),
        Station.WTC to (40.71271 to -74.01193),
        Station.CHRISTOPHER to (40.73295 to -74.00707),
        Station.NINTH to (40.73424 to -73.99910),
        Station.FOURTEENTH to (40.73735 to -73.99684),
        Station.TWENTY_THIRD to (40.74290 to -73.99278),
        Station.THIRTY_THIRD to (40.74912 to -73.98827),
    )

    /** Metres between a point and a station, along the ground. */
    fun metres(lat: Double, lon: Double, station: Station): Double {
        val (sLat, sLon) = at.getValue(station)
        val r = 6_371_000.0
        val dLat = Math.toRadians(sLat - lat)
        val dLon = Math.toRadians(sLon - lon)
        val a = Math.sin(dLat / 2).let { it * it } + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(sLat)) * Math.sin(dLon / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(a))
    }

    /** The closest station and how far it is, or null when the point is nowhere near PATH (over [withinMetres]). */
    fun nearest(lat: Double, lon: Double, withinMetres: Double = 40_000.0): Pair<Station, Double>? =
        Station.entries.map { it to metres(lat, lon, it) }.minByOrNull { it.second }?.takeIf { it.second <= withinMetres }

    /** "350 m", "1.2 km": rounded so an approximate fix does not read as a precise one. */
    fun distance(metres: Double): String = when {
        metres < 950 -> "${(Math.round(metres / 50.0) * 50).coerceAtLeast(50)} m"
        else -> "${Math.round(metres / 100.0) / 10.0} km"
    }
}
