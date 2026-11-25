package io.easypi.mobile.dashboard

import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class DnsStatsTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val date = LocalDate.of(2026, 10, 30)
    private fun at(hour: Int, minute: Int = 0) = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test fun dnsOutcomesShareOneDenominatorAndKeepTheirRequestHour() {
        val stats = DnsStatsAccumulator(zone) { date }
        repeat(104) { stats.record("example.test", it < 46, at(18, 59)) }
        val batch = stats.drain().single()
        assertEquals(18, batch.hour)
        assertEquals(104, batch.queries)
        assertEquals(46, batch.blocked)
        assertEquals(46, batch.domains["example.test"])
        assertEquals("44.2%", percentageLabel(dnsBlockPercentage(batch.queries, batch.blocked)))
        assertTrue(stats.drain().isEmpty())
    }

    @Test fun midnightDoesNotAssignYesterdayToToday() {
        var today = date
        val stats = DnsStatsAccumulator(zone) { today }
        stats.record("yesterday.test", true, at(23, 59))
        val beforeMidnight = stats.drain()
        today = date.plusDays(1)
        stats.restore(beforeMidnight)
        stats.record("late.test", true, at(23, 59))
        stats.record("today.test", false, today.atStartOfDay(zone).toInstant().toEpochMilli())
        val batch = stats.drain().single()
        assertEquals(today, batch.date)
        assertEquals(0, batch.hour)
        assertEquals(1, batch.queries)
        assertEquals(0, batch.blocked)
    }

    @Test fun failedPersistenceCanBeRestoredWithoutLosingNewEvents() {
        val stats = DnsStatsAccumulator(zone) { date }
        stats.record("blocked.test", true, at(6))
        val batch = stats.drain()
        stats.record("allowed.test", false, at(7))
        stats.restore(batch)
        assertEquals(2, stats.drain().sumOf { it.queries })
    }

    @Test fun percentageUsesEnglishFormattingAndHandlesZero() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMAN)
            assertEquals("44.2%", percentageLabel(dnsBlockPercentage(104, 46)))
            assertEquals("0.0%", percentageLabel(dnsBlockPercentage(0, 0)))
            assertEquals("100.0%", percentageLabel(dnsBlockPercentage(10, 10)))
        } finally { Locale.setDefault(previous) }
    }
}
