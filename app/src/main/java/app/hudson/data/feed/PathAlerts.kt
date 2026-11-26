package app.hudson.data.feed

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One message PATH sent: a delay, an elevator outage, an advisory, or the update that ends one. */
data class AlertMessage(
    val id: Long,
    val subject: String,
    val message: String,
    val sentAtMs: Long,
    /** PATH marks a message Open while it still applies and Closed once it is over or replaced. */
    val open: Boolean,
    /** PATH's own name for the kind of message, e.g. "PATH Elevator - Journal Square JSQ - ToNY". */
    val template: String,
)

data class AlertsSnapshot(val messages: List<AlertMessage>, val fetchedAtMs: Long)

/**
 * Parser for PATH's alert feed, the one behind the alerts on panynj.gov. Shape (checked Nov
 * 2026): a list of { ebid, messageid, sentdate2 "/Date(epoch ms)/", Subject, SentMessage,
 * TemplateName, state "Open"/"Closed", ... }. Text is plain, sometimes ending in blank lines.
 * The feed answers for at most the last 24 hours; asking for more returns nothing.
 */
object PathAlerts {
    const val URL = "https://www.panynj.gov/bin/portauthority/alerts?agency=PATH&hours=24"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val stamp = Regex("""\((-?\d+)""")

    @Serializable
    private data class Dto(
        val messageid: Long? = null,
        val ebid: Long? = null,
        val sentdate2: String? = null,
        val Subject: String? = null,
        val SentMessage: String? = null,
        val TemplateName: String? = null,
        val state: String? = null,
    )

    fun parse(body: String, fetchedAtMs: Long): AlertsSnapshot {
        val items = json.decodeFromString(ListSerializer(Dto.serializer()), body)
        val messages = items.mapNotNull { d ->
            val subject = d.Subject?.trim().orEmpty()
            val text = d.SentMessage?.trim().orEmpty()
            if (subject.isEmpty() && text.isEmpty()) return@mapNotNull null
            AlertMessage(
                id = d.messageid ?: d.ebid ?: (subject + text).hashCode().toLong(),
                subject = subject,
                message = text,
                sentAtMs = d.sentdate2?.let { stamp.find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: fetchedAtMs,
                open = d.state?.trim().equals("open", ignoreCase = true),
                template = d.TemplateName?.trim().orEmpty(),
            )
        }.sortedByDescending { it.sentAtMs }
        return AlertsSnapshot(messages, fetchedAtMs)
    }
}
