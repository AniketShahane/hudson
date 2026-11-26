package app.hudson.domain

import app.hudson.core.Line
import app.hudson.core.Station
import app.hudson.data.feed.AlertMessage
import app.hudson.data.feed.AlertsSnapshot

enum class AlertKind(val label: String) {
    /** Trains are late, rerouted or not running. */
    SERVICE("SERVICE"),
    /** Lifts and escalators. */
    ELEVATOR("ELEVATOR"),
    WEATHER("WEATHER"),
    NOTICE("NOTICE"),
}

/** An alert ready to show: PATH's words tidied, and what it touches worked out from them. */
data class Alert(
    val id: Long,
    val title: String,
    val body: String,
    val sentAtMs: Long,
    val kind: AlertKind,
    val lines: List<Line>,
    val stations: List<Station>,
)

object Alerts {
    private val lead = Regex("""^\s*(PATHAlert(\s+Final\s+Update)?\s*:\s*)+""", RegexOption.IGNORE_CASE)
    private val clock = Regex("""^\s*\d{1,2}:\d{2}\s*[AP]M\s*:\s*""", RegexOption.IGNORE_CASE)
    private val promo = Regex("""\s*Download the RidePATH app\.?""", RegexOption.IGNORE_CASE)
    private val space = Regex("""\s+""")
    private val sentences = Regex("""(?<=[.!?])\s+""")
    private val running = Regex("""\brunning\b""", RegexOption.IGNORE_CASE)
    private val hedged = Regex("""delay|suspend|not running|limited|reduced|every \d""", RegexOption.IGNORE_CASE)

    /** How each station appears in PATH's alert text. Line codes are removed before these are looked for. */
    private val names: List<Pair<Station, Regex>> = listOf(
        Station.NEWARK to """\bNWK\b|\bNewark\b""",
        Station.HARRISON to """\bHAR\b|\bHarrison\b""",
        Station.JOURNAL_SQUARE to """\bJSQ\b|\bJournal\s+Sq""",
        Station.GROVE to """\bGRV\b|\bGrove\b""",
        Station.EXCHANGE_PLACE to """\bEXPL?\b|\bEX\s?PL\b|\bExch(ange)?\s+Pl""",
        Station.NEWPORT to """\bNWPT\b|\bNewport\b""",
        Station.HOBOKEN to """\bHOB\b|\bHoboken\b""",
        Station.WTC to """\bWTC\b|\bWorld\s+Trade""",
        Station.CHRISTOPHER to """\bCHR\b|\bChristopher\b""",
        Station.NINTH to """\b9(th)?\s+St""",
        Station.FOURTEENTH to """\b14(th)?\s+St""",
        Station.TWENTY_THIRD to """\b23(rd)?\s+St""",
        Station.THIRTY_THIRD to """\b33(rd)?\s+St""",
    ).map { (s, p) -> s to Regex(p, RegexOption.IGNORE_CASE) }

    private fun tidy(s: String) = s.replace(space, " ").trim()

    fun of(m: AlertMessage): Alert {
        val title = tidy(m.subject.replace(lead, "")).ifEmpty { "PATH alert" }
        val body = tidy(m.message.replace(clock, "").replace(lead, "").replace(promo, ""))
        val all = "$title. $body"
        // PATH often ends a delay notice by listing the lines that are fine ("JSQ-33, HOB-WTC, HOB-33
        // running."). Those lines are not affected, so that sentence is left out when looking for lines.
        val troubled = all.split(sentences).filterNot { running.containsMatchIn(it) && !hedged.containsMatchIn(it) }
        val lines = Line.entries.filter { l -> troubled.any { it.contains(l.code, ignoreCase = true) } }
        val withoutCodes = Line.entries.fold(all) { acc, l -> acc.replace(l.code, " ", ignoreCase = true) }
        // A station named only as where trains are heading ("33 St-bound track") is not where the trouble is.
        val withoutBound = withoutCodes.replace(Regex("""\b[\w ]{2,20}?-bound\b""", RegexOption.IGNORE_CASE), " ")
        val stations = names.filter { (_, r) -> r.containsMatchIn(withoutBound) }.map { it.first }
        val low = (m.template + " " + all).lowercase()
        val kind = when {
            "elevator" in low || "escalator" in low -> AlertKind.ELEVATOR
            "weather" in low || "storm" in low -> AlertKind.WEATHER
            lines.isNotEmpty() || listOf("delay", "suspend", "resum", "single-track", "service", "track").any { it in low } -> AlertKind.SERVICE
            else -> AlertKind.NOTICE
        }
        return Alert(m.id, title, body, m.sentAtMs, kind, lines, stations)
    }

    /**
     * What applies right now. PATH repeats a standing advisory every few hours and replaces an
     * alert with an updated one, so only the newest message under each heading counts, and only
     * while PATH still marks it open. Trouble with trains comes first.
     */
    fun current(snapshot: AlertsSnapshot?): List<Alert> = snapshot?.messages.orEmpty()
        .groupBy { tidy(it.subject.replace(lead, "")).lowercase() }
        .mapNotNull { (_, same) -> same.maxByOrNull { it.sentAtMs }?.takeIf { it.open } }
        .map(::of)
        .sortedWith(compareBy<Alert> { it.kind.ordinal }.thenByDescending { it.sentAtMs })

    /** What ended in the last day, newest first: the "resuming normal service" messages people look for. */
    fun earlier(snapshot: AlertsSnapshot?, limit: Int = 8): List<Alert> = snapshot?.messages.orEmpty()
        .filter { !it.open }
        .sortedByDescending { it.sentAtMs }
        .distinctBy { tidy(it.subject.replace(lead, "")).lowercase() }
        .take(limit)
        .map(::of)

    /** The alerts that matter at [station]: ones that name it or a line through it, and anything about trains with no place given. */
    fun at(station: Station, alerts: List<Alert>): List<Alert> {
        val through = Line.serving(station)
        return alerts.filter { a ->
            station in a.stations || a.lines.any { it in through } ||
                (a.kind == AlertKind.SERVICE && a.stations.isEmpty() && a.lines.isEmpty())
        }
    }

    /** Whether trains themselves are affected, as opposed to a lift or the weather. */
    fun disrupts(alerts: List<Alert>): Boolean = alerts.any { it.kind == AlertKind.SERVICE }
}
