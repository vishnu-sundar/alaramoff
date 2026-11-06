package dev.alarmoff.clock

import android.app.KeyguardManager
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import dev.alarmoff.core.*
import kotlinx.coroutines.delay

private data class Frame(val reading: Reading, val nodes: Map<Int, AccessibilityNodeInfo>)

internal class AndroidClockPort(private val service: ClockService, private val pkg: String) : ClockPort {
    private var window: Int? = null

    fun guard() {
        if (!ClockSession.consent(service) || !service.getSystemService(PowerManager::class.java).isInteractive ||
            service.getSystemService(KeyguardManager::class.java).isDeviceLocked)
            throw ClockProblem("Unlock the phone and keep Clock visible. The operation stopped.")
    }

    suspend fun awaitList() {
        val until = SystemClock.elapsedRealtime() + 12_000
        var detail = "OnePlus Clock did not open. Open its Alarm tab, then try Refresh."
        var sawClock = false
        while (SystemClock.elapsedRealtime() < until) {
            guard()
            val root = service.rootInActiveWindow
            val owner = root?.packageName?.toString()
            if (owner == pkg) {
                sawClock = true
                try {
                    frame()
                    window = root.windowId
                    return
                } catch (problem: ClockProblem) { detail = problem.message.orEmpty() }
            } else if (sawClock || (owner != null && owner != service.packageName && !owner.contains("launcher"))) {
                throw ClockProblem("Clock lost focus before the list was ready.")
            }
            delay(160)
        }
        throw ClockProblem(detail)
    }

    private fun frame(): Frame {
        guard()
        val root = service.rootInActiveWindow ?: throw ClockProblem("Clock is no longer visible.")
        if (root.packageName?.toString() != pkg || (window != null && root.windowId != window))
            throw ClockProblem("Clock changed screens or opened a prompt. Review it in Clock before retrying.")
        val refs = mutableMapOf<Int, AccessibilityNodeInfo>()
        fun snapshot(n: AccessibilityNodeInfo, depth: Int): UiNode {
            if (depth > 56 || refs.size >= 1600) throw ClockProblem("This Clock screen is too complex to read safely.")
            val handle = refs.size
            refs[handle] = n
            val actions = n.actionList.map { it.id }
            val children = (0 until n.childCount).mapNotNull { index ->
                n.getChild(index)?.takeIf { it.isVisibleToUser }?.let { snapshot(it, depth + 1) }
            }
            return UiNode(handle, n.viewIdResourceName.orEmpty().substringAfterLast('/').lowercase(),
                n.className?.toString().orEmpty(), n.text?.toString().orEmpty(), n.contentDescription?.toString().orEmpty(),
                n.isCheckable, n.isChecked, n.isSelected, n.isScrollable,
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in actions || AccessibilityAction.ACTION_SCROLL_UP.id in actions,
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in actions || AccessibilityAction.ACTION_SCROLL_DOWN.id in actions,
                n.collectionItemInfo?.rowIndex?.takeIf { it >= 0 },
                n.collectionInfo?.rowCount?.takeIf { it >= 0 }, children)
        }
        val tree = snapshot(root, 0)
        return Frame(ClockReader.read(tree), refs)
    }

    override suspend fun page(): Page = frame().reading.page

    override suspend fun scroll(forward: Boolean, from: Page): Page {
        val before = frame()
        if (before.reading.page != from && before.reading.page.signature() != from.signature())
            throw ClockProblem("The visible rows changed during scrolling.")
        val list = before.nodes.getValue(before.reading.listNode)
        val actions = list.actionList.map { it.id }
        val candidates = if (forward) listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityAction.ACTION_SCROLL_DOWN.id)
            else listOf(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, AccessibilityAction.ACTION_SCROLL_UP.id)
        val action = candidates.firstOrNull { it in actions }
        if (action == null || !list.performAction(action)) throw ClockProblem("Clock declined to scroll its alarm list.")
        val deadline = SystemClock.elapsedRealtime() + 3200
        var last: Page? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(140)
            val now = page()
            val changed = now.signature() != from.signature() || now.forward != from.forward || now.back != from.back
            if (changed && last != null && last.signature() == now.signature() && last.forward == now.forward && last.back == now.back) return now
            last = now
        }
        throw ClockProblem("Clock did not expose a settled next page.")
    }

    override suspend fun tapOnce(key: AlarmKey, enable: Boolean) {
        val live = frame()
        val entry = live.reading.page.entries.singleOrNull { it.alarm.key == key }
            ?: throw ClockProblem("The chosen alarm is no longer uniquely visible.")
        val node = live.nodes.getValue(entry.control)
        if (!node.refresh() || !node.isVisibleToUser || !node.isEnabled || !node.isCheckable)
            throw ClockProblem("The alarm switch is unavailable.")
        if (node.isChecked == enable) return
        if (!node.isClickable || node.actionList.none { it.id == AccessibilityNodeInfo.ACTION_CLICK })
            throw ClockProblem("Clock does not allow this switch to be pressed through accessibility.")
        guard()
        if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            throw ClockProblem("Clock declined the switch action. Verify the alarm in Clock.")
    }

    override suspend fun settleAfterTap(key: AlarmKey, enable: Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 2400
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(160)
            val rows = page().entries.filter { it.alarm.key == key }
            if (rows.size > 1) throw ClockProblem("A matching alarm became ambiguous.")
            if (rows.isEmpty() || rows.single().alarm.enabled == enable) return
        }
        throw ClockProblem("Clock did not confirm ${key.time}. The click will not be repeated.")
    }
}
