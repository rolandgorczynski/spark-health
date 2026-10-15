package local.spark.steps

import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PrototypeTest {
    private val fixture = Fixtures.steps()
    @Test fun stableIdentityAndStrictPayload() {
        val mapped = StepMapper.map(fixture)
        assertEquals(mapped, StepMapper.map(fixture.copy()))
        assertEquals("hc1.df965816740ce6f63e97cf012c63f835abab422f2321f627f66870fbde3e0b1b", mapped.id)
        assertTrue(mapped.id.matches(Regex("hc1\\.[0-9a-f]{64}")))
        assertEquals(mapped.id, StepMapper.map(fixture.copy(count = 124)).id)
        assertNotEquals(mapped.id, StepMapper.map(fixture.copy(origin = "other.package")).id)
        assertNotEquals(mapped.id, StepMapper.map(fixture.copy(recordId = "other")).id)
        assertEquals("{\"event_id\":\"${mapped.id}\",\"type\":\"steps\",\"start\":\"2026-03-29T00:30:00Z\",\"end\":\"2026-03-29T01:30:00Z\",\"count\":123}", mapped.json)
    }
    @Test fun utcAcrossDstAndUnambiguousIdentity() {
        val a = fixture.copy(start = OffsetDateTime.parse("2026-03-29T01:30:00+01:00").toInstant(),
            end = OffsetDateTime.parse("2026-03-29T03:30:00+02:00").toInstant())
        assertEquals(StepMapper.map(fixture), StepMapper.map(a))
        assertNotEquals(StepMapper.map(fixture.copy(origin = "a|b", recordId = "c")).id,
            StepMapper.map(fixture.copy(origin = "a", recordId = "b|c")).id)
    }
    @Test fun invalidRecordsAreQuarantinedWithoutClamping() {
        val queue = FakeOutbox()
        listOf(fixture.copy(count = -1), fixture.copy(count = 1_000_001),
            fixture.copy(end = fixture.start), fixture.copy(start = Instant.MAX),
            fixture.copy(recordId = "")).forEach(queue::enqueue)
        assertEquals(0, queue.size)
        assertEquals(setOf("invalid_count", "invalid_interval", "missing_identity"), queue.reasons)
        StepMapper.map(fixture.copy(count = 0))
        StepMapper.map(fixture.copy(count = 1_000_000))
    }
    @Test fun lostAcknowledgementResendsExactBytesAndBoundsRetries() {
        val queue = FakeOutbox().apply { enqueue(fixture) }
        val receiver = FakeTransport(listOf(null, 503, 200))
        repeat(4) { queue.deliver(receiver) }
        assertEquals(3, receiver.sent.size)
        assertEquals(1, receiver.sent.distinct().size)
        assertEquals(FakeOutbox.State.ACKNOWLEDGED, queue.snapshot().single().state)
        val offline = FakeOutbox().apply { enqueue(fixture) }
        val unavailable = FakeTransport(List(6) { null })
        repeat(6) { offline.deliver(unavailable) }
        assertEquals(3, unavailable.sent.size)
    }
    @Test fun correctionsDeletesAuthAndConflictNeverBecomeNewEvents() {
        val queue = FakeOutbox().apply { enqueue(fixture); enqueue(fixture) }
        queue.deliver(FakeTransport())
        queue.enqueue(fixture.copy(count = 999))
        assertEquals(1, queue.size)
        assertEquals(FakeOutbox.State.QUARANTINED, queue.snapshot().single().state)
        queue.tombstone(StepMapper.map(fixture).id)
        assertTrue(queue.reasons.contains("deletion_requires_reconciliation"))
        for ((code, expected) in listOf(401 to FakeOutbox.State.AUTH_PAUSED,
            403 to FakeOutbox.State.AUTH_PAUSED, 409 to FakeOutbox.State.QUARANTINED,
            400 to FakeOutbox.State.QUARANTINED, 201 to FakeOutbox.State.ACKNOWLEDGED)) {
            val other = FakeOutbox().apply { enqueue(fixture) }
            val receiver = FakeTransport(listOf(code))
            repeat(2) { other.deliver(receiver) }
            assertEquals(expected, other.snapshot().single().state)
            assertEquals(1, receiver.sent.size)
        }
        queue.clear()
        assertEquals(0, queue.size)
        assertTrue(queue.reasons.isEmpty())
    }
    @Test fun paginationFiltersOriginAndAcceptsBothEndings() = runBlocking {
        for (ending in listOf(null, "")) {
            val consumed = mutableListOf<SourceSteps>()
            val tokens = mutableListOf<String?>()
            readPages(Fixtures.ORIGIN, { true }, { token ->
                tokens.add(token)
                if (token == null) listOf(fixture, fixture.copy(origin = "other.package")) to "next"
                else emptyList<SourceSteps>() to ending
            }, { consumed.addAll(it) })
            assertEquals(listOf(null, "next"), tokens)
            assertEquals(listOf(fixture), consumed)
        }
    }
    @Test fun revocationOrLeavingDiscardsInFlightPage() = runBlocking {
        var checks = 0
        var consumed = false
        try {
            readPages(Fixtures.ORIGIN, { ++checks == 1 }, { listOf(fixture) to null }, { consumed = true })
            fail("Expected permission/foreground gate")
        } catch (_: IllegalStateException) { }
        assertFalse(consumed)
    }
    @Test fun loopingPaginationStops() = runBlocking {
        try {
            readPages(Fixtures.ORIGIN, { true }, { emptyList<SourceSteps>() to "loop" }, {})
            fail("Expected loop guard")
        } catch (_: IllegalStateException) { }
    }
}
