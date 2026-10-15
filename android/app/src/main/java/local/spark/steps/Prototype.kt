package local.spark.steps

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.Instant

/** Origin is the actual DataOrigin package, never inferred from a display label. */
data class SourceSteps(val origin: String, val recordId: String, val start: Instant, val end: Instant, val count: Long)
data class Mapped(val id: String, val json: String)

object StepMapper {
    fun map(record: SourceSteps): Mapped {
        require(record.origin.isNotBlank() && record.recordId.isNotBlank()) { "missing_identity" }
        require(record.start < record.end) { "invalid_interval" }
        require(record.count in 0..1_000_000L) { "invalid_count" }
        // Length-prefixed UTF-8 fields prevent delimiter ambiguity. Version fixes field order/encoding.
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            listOf(record.origin, "StepsRecord", record.recordId, "").forEach {
                val field = it.toByteArray(Charsets.UTF_8)
                out.writeInt(field.size)
                out.write(field)
            }
        }
        val id = "hc1." + MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        // Instant.toString is canonical UTC; only digest, instants and integer enter JSON.
        return Mapped(id, "{\"event_id\":\"$id\",\"type\":\"steps\",\"start\":\"${record.start}\",\"end\":\"${record.end}\",\"count\":${record.count}}")
    }
}

/** Deliberately volatile fake storage. No filesystem, credentials, HTTP or production adapter. */
class FakeOutbox {
    enum class State { PENDING, ACKNOWLEDGED, QUARANTINED, AUTH_PAUSED }
    data class Entry(val event: Mapped, var state: State = State.PENDING, var attempts: Int = 0)
    private val entries = linkedMapOf<String, Entry>()
    val reasons = mutableSetOf<String>()
    val size get() = entries.size
    fun snapshot(): List<Entry> = entries.values.map { it.copy() }
    fun enqueue(record: SourceSteps) {
        val mapped = try { StepMapper.map(record) } catch (e: IllegalArgumentException) {
            reasons.add(e.message ?: "invalid_record"); return
        }
        val previous = entries[mapped.id]
        if (previous == null) entries[mapped.id] = Entry(mapped)
        else if (previous.event != mapped) {
            previous.state = State.QUARANTINED
            reasons.add("correction_requires_reconciliation")
        }
    }
    fun tombstone(id: String) {
        entries[id]?.state = State.QUARANTINED
        reasons.add("deletion_requires_reconciliation")
    }
    fun deliver(transport: FakeTransport) {
        entries.values.filter { it.state == State.PENDING && it.attempts < 3 }.forEach { entry ->
            entry.attempts++
            entry.state = when (transport.send(entry.event)) {
                200, 201 -> State.ACKNOWLEDGED
                401, 403 -> State.AUTH_PAUSED
                null, 503 -> State.PENDING // manual retry only; maximum three attempts
                else -> { reasons.add("contract_reconciliation"); State.QUARANTINED }
            }
        }
    }
    fun clear() { entries.clear(); reasons.clear() }
}

/** Scripted local receiver; null simulates a lost acknowledgement/connection failure. */
class FakeTransport(private val responses: List<Int?> = listOf(201)) {
    val sent = mutableListOf<Mapped>()
    fun send(event: Mapped): Int? {
        sent.add(event)
        return responses.getOrElse(sent.lastIndex) { 200 }
    }
}

/** One bounded snapshot. Reject looping providers; accept null OR empty terminal tokens. */
suspend fun readPages(
    origin: String,
    active: suspend () -> Boolean,
    page: suspend (String?) -> Pair<List<SourceSteps>, String?>,
    consume: (List<SourceSteps>) -> Unit
) {
    require(origin.isNotBlank())
    var token: String? = null
    val seen = mutableSetOf<String>()
    do {
        check(active()) { "foreground_or_permission_lost" }
        val (records, next) = page(token)
        check(active()) { "foreground_or_permission_lost" }
        consume(records.filter { it.origin == origin })
        token = next?.takeIf { it.isNotEmpty() }
        if (token != null) check(seen.add(token)) { "repeated_page_token" }
    } while (token != null)
}

object Fixtures {
    const val ORIGIN = "synthetic.mi.fitness"
    fun steps() = SourceSteps(ORIGIN, "fixture-1", Instant.parse("2026-03-29T00:30:00Z"),
        Instant.parse("2026-03-29T01:30:00Z"), 123)
}
