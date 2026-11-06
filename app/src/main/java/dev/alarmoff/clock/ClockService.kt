package dev.alarmoff.clock

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import dev.alarmoff.MainActivity
import dev.alarmoff.core.*
import kotlinx.coroutines.*

class ClockService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onServiceConnected() { super.onServiceConnected(); ClockSession.bind(this) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    fun start(target: ClockTarget, request: Request, openClock: () -> Unit) {
        if (job?.isActive == true || ClockSession.state.value.busy) return
        if (!ClockSession.consent(this)) { ClockSession.fail("Review Clock access in AlarmOff first."); return }
        ClockSession.begin()
        try { openClock() } catch (_: RuntimeException) { ClockSession.fail("OnePlus Clock could not open."); return }
        job = scope.launch {
            val verified = mutableSetOf<AlarmKey>()
            var attempted: AlarmKey? = null
            var returnToApp = true
            try {
                withTimeout(300_000) {
                    val port = AndroidClockPort(this@ClockService, target.pkg)
                    port.awaitList()
                    val engine = BulkEngine(port)
                    when (request) {
                        is Request.Read -> {
                            val result = engine.scan()
                            val message = if (result.active == 0 && request.forWidget) "No active alarms to disable."
                                else "Refreshed ${result.alarms.size} alarm(s)."
                            ClockSession.success(result, message, request.forWidget)
                        }
                        is Request.Change -> {
                            val plan = request.plan
                            val result = engine.apply(plan, { attempted = it }, {
                                verified += it
                                attempted = null
                                ClockSession.progress("Verified ${verified.size} of ${plan.keys.size}.")
                            })
                            val state = if (plan.enable) "enabled" else "disabled"
                            ClockSession.success(result, "Verified ${plan.keys.size} alarm(s) $state.", false)
                        }
                    }
                }
            } catch (e: Exception) {
                val reason = when (e) {
                    is TimeoutCancellationException -> "Clock took too long to respond."
                    is CancellationException -> "Operation stopped."
                    is ClockProblem -> e.message.orEmpty()
                    else -> "Clock stopped responding."
                }
                val detail = if (request is Request.Change) {
                    " ${verified.size} of ${request.plan.keys.size} verified." +
                        (attempted?.let { " Check ${it.time} in Clock." } ?: "")
                } else ""
                if (ClockSession.consent(this@ClockService)) ClockSession.fail(reason + detail)
                if (e is CancellationException && e !is TimeoutCancellationException) returnToApp = false
            } finally {
                // Do not pull the user back from Home, the lock screen or another app.
                if (returnToApp && rootInActiveWindow?.packageName?.toString() == target.pkg &&
                    !getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked) {
                    runCatching { startActivity(Intent(this@ClockService, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
                }
            }
        }
    }

    fun cancel() { job?.cancel() }
    fun stopAccess() { cancel(); disableSelf() }
    override fun onInterrupt() { cancel() }
    override fun onUnbind(intent: Intent?): Boolean {
        cancel()
        ClockSession.unbind(this)
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        scope.cancel()
        ClockSession.unbind(this)
        super.onDestroy()
    }
}
