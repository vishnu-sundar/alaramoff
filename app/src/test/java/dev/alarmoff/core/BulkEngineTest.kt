package dev.alarmoff.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BulkEngineTest {
    private fun alarm(i: Int, on: Boolean = true) = Alarm(AlarmKey("%02d:15".format(i), "Alarm $i", "Weekdays"), on)
    private fun page(vararg alarms: Alarm, total: Int? = null) =
        Page(alarms.mapIndexed { idx, a -> Entry(a, null, idx) }, false, false, total)

    private class Fake(items: List<Alarm>, val pageSize: Int = 3) : ClockPort {
        val rows = items.toMutableList()
        val clicks = mutableListOf<AlarmKey>()
        var start = 0
        var failAt: AlarmKey? = null
        var ignoreTap = false
        var reorder = false
        override suspend fun page(): Page = Page(rows.drop(start).take(pageSize).mapIndexed { i, a -> Entry(a, start + i, i) },
            start > 0, start + pageSize < rows.size, rows.size)
        override suspend fun scroll(forward: Boolean, from: Page): Page {
            start = (start + (if (forward) pageSize - 1 else 1 - pageSize)).coerceIn(0, (rows.size - pageSize).coerceAtLeast(0))
            return page()
        }
        override suspend fun tapOnce(key: AlarmKey, enable: Boolean) {
            require(page().entries.any { it.alarm.key == key })
            clicks += key
            if (key == failAt) throw ClockProblem("Prompt from Clock")
            val i = rows.indexOfFirst { it.key == key }
            if (!ignoreTap) rows[i] = rows[i].copy(enabled = enable)
            if (reorder) rows.sortByDescending { it.enabled }
        }
        override suspend fun settleAfterTap(key: AlarmKey, enable: Boolean) {}
    }

    @Test fun startsAtTopAndReadsScrolledRowsOnce() = runBlocking {
        val rows = (5..13).map { alarm(it, it % 2 == 0) }
        val device = Fake(rows).apply { start = 4 }
        assertEquals(rows, BulkEngine(device).scan().alarms)
        assertTrue(device.clicks.isEmpty())
    }
    @Test fun disablesOnlyReviewedTargetsAndVerifiesAllPages() = runBlocking {
        val rows = (5..11).map { alarm(it, it != 6) }
        val f = Fake(rows).apply { reorder = true }
        val e = BulkEngine(f)
        val selected = setOf(rows.first().key, rows.last().key)
        val result = e.apply(Plan(e.scan(), selected, false), {}, {})
        assertEquals(selected, f.clicks.toSet())
        assertEquals(4, result.active)
    }
    @Test fun bulkEnableSkipsAlreadyEnabled() = runBlocking {
        val f = Fake(listOf(alarm(6, false), alarm(7, false)))
        val e = BulkEngine(f)
        val initial = e.scan()
        f.rows[0] = f.rows[0].copy(enabled = true)
        assertEquals(2, e.apply(Plan(initial, initial.multiplicity.keys, true), {}, {}).active)
        assertEquals(listOf(alarm(7).key), f.clicks)
    }
    @Test fun changedRosterInvalidatesConfirmation() {
        val f = Fake(listOf(alarm(6)))
        val e = BulkEngine(f)
        val plan = runBlocking { Plan(e.scan(), setOf(alarm(6).key), false) }
        f.rows += alarm(7)
        assertThrows(ClockProblem::class.java) { runBlocking { e.apply(plan, {}, {}) } }
        assertTrue(f.clicks.isEmpty())
    }
    @Test fun identicalRowsCannotBeArmed() {
        val a = alarm(7)
        val inv = Inventory(listOf(a, a.copy(enabled = false)))
        assertFalse(inv.unique(a.key))
        assertThrows(IllegalArgumentException::class.java) { Plan(inv, setOf(a.key), false) }
    }
    @Test fun unconfirmedClickIsNeverRetried() {
        val f = Fake(listOf(alarm(7))).apply { ignoreTap = true }
        val e = BulkEngine(f)
        val verified = mutableListOf<AlarmKey>()
        assertThrows(ClockProblem::class.java) { runBlocking { e.apply(Plan(e.scan(), setOf(alarm(7).key), false), {}, { verified += it }) } }
        assertEquals(1, f.clicks.size)
        assertTrue(verified.isEmpty())
    }
    @Test fun midBatchPromptStopsAllFurtherClicks() {
        val rows = listOf(alarm(6), alarm(7), alarm(8))
        val f = Fake(rows).apply { failAt = rows[1].key }
        val e = BulkEngine(f)
        val verified = mutableListOf<AlarmKey>()
        assertThrows(ClockProblem::class.java) { runBlocking { e.apply(Plan(e.scan(), rows.map { it.key }.toSet(), false), {}, { verified += it }) } }
        assertEquals(listOf(rows[0].key), verified)
        assertEquals(listOf(rows[0].key, rows[1].key), f.clicks)
    }
    @Test fun unindexedOverlapsNeedVerifiedCount() {
        val a = (4..7).map { alarm(it) }
        assertEquals(a, joinPages(listOf(page(a[0], a[1], total = 4), page(a[1], a[2], a[3], total = 4))))
        assertThrows(ClockProblem::class.java) { joinPages(listOf(page(a[0], a[1]), page(a[1], a[2]))) }
    }
    @Test fun incompletePageCannotBecomeInventory() {
        assertThrows(ClockProblem::class.java) { joinPages(listOf(page(alarm(7), total = 3))) }
    }
}
