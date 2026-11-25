package io.easypi.mobile.dashboard

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

internal data class DnsStatsBatch(
    val date: LocalDate,
    val hour: Int,
    var queries: Int = 0,
    var blocked: Int = 0,
    val domains: MutableMap<String, Int> = mutableMapOf(),
)

/** Count DNS outcomes together, in the request's local hour rather than flush time. */
internal class DnsStatsAccumulator(
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val today: () -> LocalDate = { LocalDate.now(zone) },
) {
    private val hours = mutableMapOf<Int, DnsStatsBatch>()

    @Synchronized fun record(domain: String, blocked: Boolean, occurredAtMillis: Long) {
        val time = Instant.ofEpochMilli(occurredAtMillis).atZone(zone)
        val date = today()
        hours.entries.removeAll { it.value.date != date }
        if (time.toLocalDate() != date) return
        val batch = hours.getOrPut(time.hour) { DnsStatsBatch(date, time.hour) }
        batch.queries++
        if (blocked) {
            batch.blocked++
            if (domain.isNotBlank() && (domain in batch.domains || batch.domains.size < 1024)) {
                batch.domains[domain] = (batch.domains[domain] ?: 0) + 1
            }
        }
    }

    @Synchronized fun drain(): List<DnsStatsBatch> = hours.values.toList().also { hours.clear() }

    @Synchronized fun restore(batches: List<DnsStatsBatch>) {
        val date = today()
        hours.entries.removeAll { it.value.date != date }
        batches.filter { it.date == date }.forEach { incoming ->
            val batch = hours.getOrPut(incoming.hour) { DnsStatsBatch(date, incoming.hour) }
            batch.queries += incoming.queries
            batch.blocked += incoming.blocked
            incoming.domains.forEach { (domain, hits) ->
                if (domain in batch.domains || batch.domains.size < 1024) {
                    batch.domains[domain] = (batch.domains[domain] ?: 0) + hits
                }
            }
        }
    }
}

internal fun dnsBlockPercentage(total: Int, blocked: Int): Float =
    if (total <= 0) 0f else (blocked.coerceIn(0, total) * 100.0 / total).toFloat()

internal fun percentageLabel(value: Float): String =
    String.format(Locale.ENGLISH, "%.1f%%", if (value.isFinite()) value.coerceIn(0f, 100f) else 0f)
