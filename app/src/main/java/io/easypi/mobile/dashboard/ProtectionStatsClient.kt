package io.easypi.mobile.dashboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

data class ProtectionHour(val total: Int, val blocked: Int)
data class BlockedDomain(val domain: String, val hits: Int)
data class ProtectionStats(
    val totalQueries: Int,
    val blockedQueries: Int,
    val blockPercentage: Float,
    val hourly: List<ProtectionHour>,
    val topBlocked: List<BlockedDomain>,
    val sampledAt: LocalDateTime = LocalDateTime.now(),
)

/** Statistics produced by PRISM on this Android device. */
object ProtectionStatsClient {
    private const val PREFERENCES = "phone-protection-stats"
    // Previous counters mixed policy lookups and deduplicated block events.
    // They cannot be migrated into truthful DNS request counts.
    private const val STATS_VERSION = 3
    private const val KEY_VERSION = "version"
    private const val KEY_DATE = "date"
    private const val KEY_TOTALS = "hourly-totals"
    private const val KEY_BLOCKED = "hourly-blocked"
    private const val KEY_DOMAINS = "blocked-domains"
    private val lock = Any()

    /** Persists outcomes from the native DNS observer in one preferences write. */
    internal fun recordBatches(context: Context, batches: List<DnsStatsBatch>) = synchronized(lock) {
        val date = LocalDate.now()
        val current = batches.filter { it.date == date && it.hour in 0..23 }
        if (current.isEmpty()) return@synchronized
        val state = readState(context)
        current.forEach { batch ->
            state.totals[batch.hour] += batch.queries
            state.blocked[batch.hour] += batch.blocked
            batch.domains.forEach { (domain, hits) ->
                if (domain in state.domains || state.domains.size < 1024) {
                    state.domains[domain] = (state.domains[domain] ?: 0) + hits
                }
            }
        }
        writeState(context, state)
    }

    fun load(context: Context): ProtectionStats = synchronized(lock) {
        val state = readState(context)
        val total = state.totals.sum()
        val blocked = state.blocked.sum()
        ProtectionStats(
            totalQueries = total,
            blockedQueries = blocked,
            blockPercentage = dnsBlockPercentage(total, blocked),
            hourly = List(24) { hour -> ProtectionHour(state.totals[hour], state.blocked[hour]) },
            topBlocked = state.domains.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(10)
                .map { BlockedDomain(it.key, it.value) },
        )
    }

    private fun readState(context: Context): DayState {
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        if (preferences.getInt(KEY_VERSION, 0) != STATS_VERSION || preferences.getString(KEY_DATE, null) != today) {
            return DayState(today)
        }
        return DayState(
            date = today,
            totals = decodeHours(preferences.getString(KEY_TOTALS, null)),
            blocked = decodeHours(preferences.getString(KEY_BLOCKED, null)),
            domains = decodeDomains(preferences.getString(KEY_DOMAINS, null)),
        )
    }

    private fun writeState(context: Context, state: DayState) {
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putInt(KEY_VERSION, STATS_VERSION)
            .putString(KEY_DATE, state.date)
            .putString(KEY_TOTALS, JSONArray(state.totals.toList()).toString())
            .putString(KEY_BLOCKED, JSONArray(state.blocked.toList()).toString())
            .putString(KEY_DOMAINS, JSONObject(state.domains as Map<*, *>).toString())
            .apply()
    }

    private fun decodeHours(value: String?): IntArray = IntArray(24).also { result ->
        val json = runCatching { JSONArray(value ?: "[]") }.getOrNull() ?: return@also
        for (index in 0 until minOf(24, json.length())) result[index] = json.optInt(index, 0).coerceAtLeast(0)
    }

    private fun decodeDomains(value: String?): MutableMap<String, Int> {
        val result = mutableMapOf<String, Int>()
        val json = runCatching { JSONObject(value ?: "{}") }.getOrNull() ?: return result
        json.keys().forEach { domain ->
            val hits = json.optInt(domain, 0)
            if (hits > 0) result[domain] = hits
        }
        return result
    }

    private data class DayState(
        val date: String,
        val totals: IntArray = IntArray(24),
        val blocked: IntArray = IntArray(24),
        val domains: MutableMap<String, Int> = mutableMapOf(),
    )
}
