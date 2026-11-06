package dev.alarmoff.core

import java.util.Locale

/** A key describes only the identity Clock exposes. Equal keys are ambiguous. */
data class AlarmKey(val time: String, val label: String, val repeat: String)
data class Alarm(val key: AlarmKey, val enabled: Boolean)
data class Entry(val alarm: Alarm, val position: Int?, val control: Int)
data class Page(val entries: List<Entry>, val back: Boolean, val forward: Boolean, val total: Int?)
data class Reading(val page: Page, val listNode: Int)
class ClockProblem(message: String) : Exception(message)

data class UiNode(
    val handle: Int = 0, val id: String = "", val kind: String = "", val text: String = "",
    val description: String = "", val checkable: Boolean = false, val checked: Boolean = false,
    val selected: Boolean = false, val scrollable: Boolean = false, val back: Boolean = false,
    val forward: Boolean = false, val position: Int? = null, val count: Int? = null,
    val children: List<UiNode> = emptyList(), val heading: Boolean = false
) {
    fun walk(): Sequence<UiNode> = sequence {
        yield(this@UiNode)
        for (child in children) yieldAll(child.walk())
    }
    val words get() = listOf(text, description).filter { it.isNotBlank() }.distinct()
}

object ClockReader {
    private val clockTime = Regex("""(?<!\d)([01]?\d|2[0-3])\s*[:：]\s*([0-5]\d)(?!\d)(?:\s*([ap])\.?\s*m\.?)?""", RegexOption.IGNORE_CASE)
    private val meridiem = Regex("""[ap]\.?\s*m\.?""", RegexOption.IGNORE_CASE)
    private val day = Regex("""(?i)\b(mon(?:day)?|tue(?:s(?:day)?)?|wed(?:nesday)?|thu(?:rs(?:day)?)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?|weekdays?|weekends?|daily|every ?day|once|never|does not repeat)\b""")
    private val relative = Regex("""(?i)\b(tomorrow|today|in \d+|rings? in|hours? from now|minutes? from now)\b""")
    private val listId = Regex("""(?:alarm.*(?:list|recycler)|(?:list|recycler).*alarm)""")
    private val toggleId = Regex("""(?:switch|toggle|onoff|on_off|enable|compoundbutton)""")
    private val stateWord = setOf("on", "off", "enabled", "disabled", "switch", "alarm", "alarms")

    private fun clean(value: String) = value.map { c ->
        if (c.isDigit()) c.digitToInt().digitToChar() else c
    }.joinToString("").replace(Regex("""[\p{Z}\s]+"""), " ").trim()

    private fun isToggle(n: UiNode) = n.checkable &&
        (toggleId.containsMatchIn(n.kind.lowercase()) || toggleId.containsMatchIn(n.id.lowercase()))

    private fun isList(n: UiNode) = listId.containsMatchIn(n.id) || n.scrollable || n.back || n.forward ||
        n.kind.endsWith("RecyclerView") || n.kind.endsWith("ListView") || n.kind.endsWith("ScrollView") || n.count != null

    private val otherSetting = Regex("""vibrat|snooze|ringtone|notification|skip_next|bedtime|fade_in""")

    private fun looksLikeAlarmList(nodes: List<UiNode>): Boolean {
        if (nodes.any { listId.containsMatchIn(it.id) || it.id.contains("alarm_empty") }) return true
        val tab = nodes.any { n -> (n.selected || n.heading || n.id.contains("title") || n.id.contains("heading")) &&
            n.words.any { it.equals("Alarm", true) || it.equals("Alarms", true) } }
        val collection = nodes.any { it.count != null || it.kind.endsWith("RecyclerView") || it.kind.endsWith("ListView") }
        val alarmRows = nodes.any { it.id.contains("alarm_time") } && nodes.any { isToggle(it) && it.id.contains("alarm") }
        return tab || (collection && alarmRows)
    }

    fun read(root: UiNode): Reading {
        val nodes = root.walk().toList()
        if (nodes.any { it.id.contains("dialog") || it.id.contains("bottom_sheet") || it.id == "alerttitle" })
            throw ClockProblem("Clock is displaying a prompt. Complete or cancel it in Clock.")
        if (!looksLikeAlarmList(nodes) || nodes.any { it.kind.endsWith("EditText") || it.kind.endsWith("TimePicker") || it.id.contains("alarm_editor") })
            throw ClockProblem("Open the Alarm tab in OnePlus Clock. This screen is not a readable alarm list.")
        val toggles = nodes.filter(::isToggle)
        if (toggles.any { otherSetting.containsMatchIn(it.id) })
            throw ClockProblem("Clock is showing alarm settings. Return to the Alarm list.")
        if (toggles.isEmpty()) {
            val explicitEmpty = nodes.any { n -> n.id.contains("empty") && n.id.contains("alarm") ||
                n.words.any { it.trim().lowercase() in setOf("no alarms", "no alarms set", "no alarms yet", "no alarm", "no alarms added") } }
            if (explicitEmpty && nodes.none { n -> n.words.any { clockTime.containsMatchIn(clean(it)) } })
                return Reading(Page(emptyList(), false, false, 0), root.handle)
            throw ClockProblem("Clock did not expose alarm switches or a recognized empty list.")
        }
        val containers = nodes.filter(::isList)
        val covering = containers.filter { n -> n.walk().count(::isToggle) == toggles.size }
        val scope = covering.lastOrNull() ?: root
        if (containers.any { n -> (n.back || n.forward || n.scrollable) && n !in covering && n.walk().any(::isToggle) })
            throw ClockProblem("Clock has more than one scrollable alarm group.")
        val scroller = covering.lastOrNull { it.back || it.forward || it.scrollable } ?: scope
        if (scroller.scrollable && !scroller.forward && !scroller.back)
            throw ClockProblem("Clock did not expose vertical paging controls.")
        if (scope.walk().any { it.checkable && !isToggle(it) && !it.selected })
            throw ClockProblem("Leave Clock’s selection or editing mode, then refresh.")
        val parents = mutableMapOf<Int, UiNode>()
        fun index(n: UiNode) { for (child in n.children) { parents[child.handle] = n; index(child) } }
        index(scope)
        val owned = mutableSetOf<Int>()
        val entries = toggles.map { toggle ->
            val lineage = generateSequence(toggle) { parents[it.handle] }.toList()
            val ancestors = lineage.takeWhile { it !== scope }
            val candidates = ancestors.filter { node ->
                node.walk().count(::isToggle) == 1 && node.walk().any { n -> n.words.any { clockTime.containsMatchIn(clean(it)) } }
            }
            val row = candidates.firstOrNull { it.position != null }
                ?: candidates.firstOrNull { node -> node.walk().any { it !== toggle && it.words.isNotEmpty() } }
                ?: candidates.firstOrNull()
                ?: if (toggles.size == 1 && isToggle(scope)) scope else null
            if (row == null) throw ClockProblem("A visible switch could not be matched to exactly one alarm time.")
            row.walk().forEach { owned += it.handle }
            val key = keyFor(row) ?: throw ClockProblem("Clock exposed an ambiguous alarm time. No switch can be chosen safely.")
            Entry(Alarm(key, toggle.checked), lineage.firstNotNullOfOrNull { it.position }, toggle.handle)
        }
        val visibleTimes = scope.walk().filter { it.id.contains("time") && !it.id.contains("timer") &&
            it.words.any { value -> clockTime.containsMatchIn(clean(value)) } }.toList()
        if (!scroller.back && !scroller.forward && visibleTimes.any { it.handle !in owned })
            throw ClockProblem("Some displayed times have no readable alarm switch.")
        return Reading(Page(entries, scroller.back, scroller.forward, scope.count ?: scroller.count), scroller.handle)
    }

    private fun keyFor(row: UiNode): AlarmKey? {
        val nodes = row.walk().toList()
        val words = nodes.flatMap { n -> n.words.map(::clean) }.distinct()
        val idTimes = nodes.filter { it.id.contains("time") || it.kind.endsWith("TextClock") }
            .flatMap { it.words }.map(::clean).filter { clockTime.containsMatchIn(it) }
        val timeWords = idTimes.ifEmpty { words.filter { clockTime.matchEntire(it) != null } }.ifEmpty { words }
        val matches = timeWords.flatMap { clockTime.findAll(it).toList() }
        val times = matches.map { it.groupValues[1].toInt() to it.groupValues[2] }.distinct()
        if (times.size != 1) return null
        val (hour, minute) = times.single()
        val periods = (matches.map { it.groupValues[3] }.filter { it.isNotEmpty() } +
            words.filter { meridiem.matches(it) }.map { it.take(1) }).map { it.uppercase(Locale.ROOT) }.distinct()
        if (periods.size > 1) return null
        val time = "%02d:%s".format(Locale.ROOT, hour, minute) + (periods.firstOrNull()?.let { " ${it}M" } ?: "")
        fun field(vararg ids: String) = nodes.asSequence().filter { n -> ids.any { n.id.contains(it) } }
            .flatMap { it.words.asSequence() }.map(::clean).firstOrNull { it.isNotEmpty() && !relative.containsMatchIn(it) }
        val parts = words.flatMap { it.split(Regex("""[,;•|\n]""")) }.map { it.trim() }.distinct()
        val repeat = field("repeat", "week", "schedule", "days")
            ?: parts.filter { day.containsMatchIn(it) && !clockTime.containsMatchIn(it) && !relative.containsMatchIn(it) }
                .joinToString(", ")
        val label = field("label", "alarm_name", "alarmname", "note", "remark") ?: parts.firstOrNull {
            it.isNotEmpty() && !clockTime.containsMatchIn(it) && !meridiem.matches(it) &&
                !day.containsMatchIn(it) && !relative.containsMatchIn(it) && it.lowercase() !in stateWord &&
                !it.equals(repeat, true) && it !in listOf("0", "1")
        }.orEmpty()
        return AlarmKey(time, label, repeat)
    }
}
