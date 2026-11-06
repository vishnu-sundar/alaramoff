package dev.alarmoff.core

data class Inventory(val alarms: List<Alarm>) {
    val multiplicity = alarms.groupingBy { it.key }.eachCount()
    val active = alarms.count { it.enabled }
    fun unique(key: AlarmKey) = multiplicity[key] == 1
}

data class Plan(val source: Inventory, val keys: Set<AlarmKey>, val enable: Boolean) {
    init {
        require(keys.isNotEmpty() && keys.all(source::unique))
    }
}

interface ClockPort {
    suspend fun page(): Page
    suspend fun scroll(forward: Boolean, from: Page): Page
    /** Reacquires the live control, checks its state, then sends at most one click. */
    suspend fun tapOnce(key: AlarmKey, enable: Boolean)
    suspend fun settleAfterTap(key: AlarmKey, enable: Boolean)
}

fun Page.signature() = entries.map { it.position to it.alarm }

class BulkEngine(private val clock: ClockPort) {
    private suspend fun top(): Page {
        var page = clock.page()
        val visited = mutableSetOf<List<Pair<Int?, Alarm>>>()
        while (page.back) {
            if (!visited.add(page.signature()) || visited.size > 80) throw ClockProblem("Could not verify the start of the list.")
            page = clock.scroll(false, page)
        }
        return page
    }

    suspend fun scan(): Inventory {
        var page = top()
        val pages = mutableListOf(page)
        val seen = mutableSetOf(page.signature())
        while (page.forward) {
            page = clock.scroll(true, page)
            if (!seen.add(page.signature()) || pages.size >= 80) throw ClockProblem("Clock did not expose a distinct next page.")
            pages += page
        }
        return Inventory(joinPages(pages))
    }

    private suspend fun locate(key: AlarmKey): Alarm {
        fun find(p: Page): Alarm? {
            val matches = p.entries.filter { it.alarm.key == key }
            if (matches.size > 1) throw ClockProblem("Two visible alarms match the chosen row.")
            return matches.singleOrNull()?.alarm
        }
        find(clock.page())?.let { return it }
        var p = top()
        repeat(81) {
            find(p)?.let { return it }
            if (!p.forward) throw ClockProblem("An alarm moved or was edited. Refresh and review the list.")
            p = clock.scroll(true, p)
        }
        throw ClockProblem("Could not locate the selected alarm.")
    }

    suspend fun apply(plan: Plan, onAttempt: (AlarmKey) -> Unit, onVerified: (AlarmKey) -> Unit): Inventory {
        val fresh = scan()
        if (fresh.multiplicity != plan.source.multiplicity)
            throw ClockProblem("The alarm list changed after review. Refresh to confirm the new list.")
        for (key in plan.keys) {
            if (locate(key).enabled != plan.enable) {
                onAttempt(key)
                clock.tapOnce(key, plan.enable)
                clock.settleAfterTap(key, plan.enable)
                if (locate(key).enabled != plan.enable)
                    throw ClockProblem("Clock did not confirm the switch at ${key.time}. Check it in Clock.")
            }
            onVerified(key)
        }
        val final = scan()
        if (final.multiplicity != fresh.multiplicity || plan.keys.any { key ->
                final.alarms.singleOrNull { it.key == key }?.enabled != plan.enable
            }) throw ClockProblem("The final list changed during verification. Check the alarms in Clock.")
        return final
    }
}

/** RecyclerView indices identify overlaps, never an alarm to click. */
internal fun joinPages(pages: List<Page>): List<Alarm> {
    val totals = pages.mapNotNull { it.total }.distinct()
    if (totals.size > 1) throw ClockProblem("Clock changed its row count during the scan.")
    val total = totals.singleOrNull()
    val entries = pages.flatMap { it.entries }
    val alarms = when {
        pages.size == 1 -> entries.map { it.alarm }
        entries.isNotEmpty() && entries.all { it.position != null } -> {
            val positions = sortedMapOf<Int, Alarm>()
            for (entry in entries) {
                val index = requireNotNull(entry.position)
                val old = positions.put(index, entry.alarm)
                if (old != null && old != entry.alarm)
                    throw ClockProblem("Alarm rows moved during the scan. Try Refresh again.")
            }
            if (positions.keys.toList() != positions.keys.indices.toList())
                throw ClockProblem("Clock did not expose every alarm row.")
            positions.values.toList()
        }
        total != null -> {
            val collected = pages.first().entries.map { it.alarm }.toMutableList()
            for (page in pages.drop(1)) {
                val next = page.entries.map { it.alarm }
                val overlaps = (1..minOf(next.size, collected.size)).filter { collected.takeLast(it) == next.take(it) }
                if (overlaps.size > 1) throw ClockProblem("Overlapping rows are indistinguishable.")
                collected.addAll(next.drop(overlaps.singleOrNull() ?: 0))
            }
            collected
        }
        else -> throw ClockProblem("Clock hides the row count and positions in this scrolling list. Use Clock to manage it.")
    }
    if (total != null && alarms.size != total)
        throw ClockProblem("Clock exposed ${alarms.size} of $total rows. A complete list is required.")
    return alarms
}
