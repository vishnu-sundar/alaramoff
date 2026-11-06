package dev.alarmoff.clock

import android.content.Context
import dev.alarmoff.core.Inventory
import dev.alarmoff.core.Plan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface Request {
    data class Read(val forWidget: Boolean = false) : Request
    data class Change(val plan: Plan) : Request
}

data class SessionState(
    val connected: Boolean = false,
    val busy: Boolean = false,
    val inventory: Inventory? = null,
    val readAt: Long? = null,
    val message: String? = null,
    val failed: Boolean = false,
    val widgetReview: Boolean = false
)

/** Main-thread, process-local state. Android process recreation cannot replay a command. */
object ClockSession {
    private val mutable = MutableStateFlow(SessionState())
    val state = mutable.asStateFlow()
    var service: ClockService? = null
        private set
    private fun prefs(c: Context) = c.getSharedPreferences("consent", Context.MODE_PRIVATE)
    fun consent(c: Context) = prefs(c).getBoolean("clock_access", false)
    fun consent(c: Context, granted: Boolean) {
        prefs(c).edit().putBoolean("clock_access", granted).apply()
        if (!granted) {
            service?.stopAccess()
            mutable.value = SessionState()
        }
    }
    fun bind(s: ClockService) { service = s; mutable.value = mutable.value.copy(connected = true) }
    fun unbind(s: ClockService) {
        if (service !== s) return
        service = null
        val old = mutable.value
        mutable.value = if (old.busy) SessionState(message = "Clock access ended. Check any switches already operated.", failed = true)
            else old.copy(connected = false, inventory = null, readAt = null, widgetReview = false)
    }
    fun begin() { mutable.value = mutable.value.copy(busy = true, message = "Opening the alarm list…", failed = false, widgetReview = false) }
    fun progress(message: String) { mutable.value = mutable.value.copy(message = message) }
    fun success(inventory: Inventory, message: String, widget: Boolean) {
        mutable.value = mutable.value.copy(inventory = inventory, readAt = System.currentTimeMillis(),
            busy = false, failed = false, message = message, widgetReview = widget && inventory.active > 0)
    }
    fun fail(message: String) {
        mutable.value = mutable.value.copy(busy = false, inventory = null, readAt = null,
            failed = true, message = message, widgetReview = false)
    }
    fun consumeReview() { mutable.value = mutable.value.copy(widgetReview = false) }
}
