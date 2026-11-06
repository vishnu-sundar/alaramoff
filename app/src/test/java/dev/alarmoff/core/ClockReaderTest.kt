package dev.alarmoff.core

import org.junit.Assert.*
import org.junit.Test

class ClockReaderTest {
    private var next = 1
    private fun text(id: String, value: String) = UiNode(next++, id = id, text = value)
    private fun toggle(on: Boolean) = UiNode(next++, id = "alarm_switch", kind = "android.widget.Switch", checkable = true, checked = on)
    private fun row(vararg fields: UiNode) = UiNode(next++, children = fields.toList())
    private fun list(vararg fields: UiNode) = UiNode(next++, id = "alarm_list", kind = "androidx.recyclerview.widget.RecyclerView", children = fields.toList())

    @Test fun readsTimeLabelScheduleAndStateWithoutUsingPositionAsIdentity() {
        val root = list(row(text("alarm_time", "7:05"), text("am_pm", "PM"), text("alarm_name", "Work"),
            text("repeat_days", "Mon, Wed"), toggle(true)), row(text("alarm_time", "٠٧:٠٥"), text("am_pm", "AM"), toggle(false)))
        val entries = ClockReader.read(root).page.entries
        assertEquals(Alarm(AlarmKey("07:05 PM", "Work", "Mon, Wed"), true), entries.first().alarm)
        assertEquals(Alarm(AlarmKey("07:05 AM", "", ""), false), entries.last().alarm)
    }
    @Test fun mergedDescriptionWorksAndCountdownDoesNotBecomeLabel() {
        val button = toggle(false).copy(description = "08:15 AM, Breakfast, Weekdays, Off")
        val expected = AlarmKey("08:15 AM", "Breakfast", "Weekdays")
        assertEquals(expected, ClockReader.read(list(row(button))).page.entries.single().alarm.key)
        val dynamic = row(text("time", "08:15 AM"), text("", "in 3 hours"), toggle(false))
        assertEquals("", ClockReader.read(list(dynamic)).page.entries.single().alarm.key.label)
    }
    @Test fun distinguishesSameTimeWhenLabelsDiffer() {
        val root = list(row(text("time", "06:30"), text("alarm_label", "School"), toggle(true)),
            row(text("time", "06:30"), text("alarm_label", "Gym"), toggle(false)))
        assertEquals(2, ClockReader.read(root).page.entries.map { it.alarm.key }.distinct().size)
    }
    @Test fun rejectsOrphanSwitchAndMultipleTimes() {
        assertThrows(ClockProblem::class.java) { ClockReader.read(list(row(toggle(true)))) }
        assertThrows(ClockProblem::class.java) {
            ClockReader.read(list(row(text("time", "07:00"), text("time", "08:00"), toggle(true))))
        }
    }
    @Test fun editAndUnrelatedPagesFailClosed() {
        val r = row(text("world_clock_time", "09:00"), toggle(false).copy(id = "some_switch"))
        assertThrows(ClockProblem::class.java) { ClockReader.read(r) }
        assertThrows(ClockProblem::class.java) { ClockReader.read(list(r, UiNode(next++, kind = "android.widget.EditText"))) }
    }
    @Test fun onlyExplicitEmptyStateMeansZero() {
        assertTrue(ClockReader.read(list(text("alarm_empty", "No alarms"))).page.entries.isEmpty())
        assertThrows(ClockProblem::class.java) { ClockReader.read(list()) }
    }
    @Test fun rejectsEditorWithOnlyTimeAndVibrationSwitch() {
        val editor = row(row(text("alarm_time", "07:15"), toggle(true).copy(id = "vibrate_switch")))
        assertThrows(ClockProblem::class.java) { ClockReader.read(editor) }
        assertThrows(ClockProblem::class.java) { ClockReader.read(list(editor)) }
    }
    @Test fun identicalTimeInRowWithoutSwitchCannotBeHiddenByOtherAlarm() {
        val root = list(row(text("alarm_time", "07:15"), toggle(true)), row(text("alarm_time", "07:15")))
        assertThrows(ClockProblem::class.java) { ClockReader.read(root) }
    }
}
