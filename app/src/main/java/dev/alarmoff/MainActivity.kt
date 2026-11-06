package dev.alarmoff

import android.app.Activity
import android.app.Application
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.alarmoff.clock.*
import dev.alarmoff.core.*
import dev.alarmoff.ui.*
import kotlinx.coroutines.flow.MutableStateFlow

class AlarmViewModel(app: Application) : AndroidViewModel(app) {
    val session = ClockSession.state
    var consent by mutableStateOf(ClockSession.consent(app))
        private set
    var clock by mutableStateOf(ClockTarget.detect(app))
        private set
    var selected by mutableStateOf(emptySet<AlarmKey>())
        private set
    var review by mutableStateOf<Plan?>(null)
        private set

    fun sync() { clock = ClockTarget.detect(getApplication()); consent = ClockSession.consent(getApplication()) }
    fun allow(grant: Boolean) {
        ClockSession.consent(getApplication(), grant)
        consent = grant
        if (!grant) { clear(); review = null; WidgetRequest.pending.value = false }
    }
    fun toggle(key: AlarmKey) { selected = if (key in selected) selected - key else selected + key }
    fun all(inv: Inventory) { selected = inv.alarms.map { it.key }.filter(inv::unique).toSet() }
    fun clear() { selected = emptySet() }
    fun prepare(inv: Inventory, enable: Boolean, allActive: Boolean = false) {
        val keys = inv.alarms.filter { it.enabled != enable && (allActive || it.key in selected) }.map { it.key }.toSet()
        if (keys.isNotEmpty() && keys.all(inv::unique)) review = Plan(inv, keys, enable)
    }
    fun dismiss() { review = null }
    fun read(activity: Activity, widget: Boolean = false) {
        dismiss(); clear()
        start(activity, Request.Read(widget))
    }
    fun confirm(activity: Activity) {
        val plan = review ?: return
        dismiss(); clear()
        start(activity, Request.Change(plan))
    }
    private fun start(activity: Activity, request: Request) {
        val target = clock ?: return ClockSession.fail("OnePlus Clock could not be found.")
        val service = ClockSession.service ?: return ClockSession.fail("Enable AlarmOff Clock access in Android Settings.")
        if (!consent || session.value.busy) return
        service.start(target, request) { activity.startActivity(target.intent) }
    }
}

class MainActivity : ComponentActivity() {
    private var resumeCount by mutableIntStateOf(0)
    override fun onResume() { super.onResume(); resumeCount++ }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val model: AlarmViewModel = viewModel()
            val state by model.session.collectAsStateWithLifecycle()
            val widget by WidgetRequest.pending.collectAsStateWithLifecycle()
            LaunchedEffect(resumeCount) { model.sync() }
            LaunchedEffect(widget, model.consent, model.clock?.pkg, state.connected, state.busy) {
                if (widget && model.consent && model.clock != null && state.connected && !state.busy) {
                    WidgetRequest.pending.value = false
                    model.read(this@MainActivity, widget = true)
                }
            }
            LaunchedEffect(state.widgetReview) {
                if (state.widgetReview) {
                    state.inventory?.let { model.prepare(it, false, allActive = true) }
                    ClockSession.consumeReview()
                }
            }
            AlarmTheme { AlarmScreen(model, state, this, widget) }
        }
    }
}

private object WidgetRequest { val pending = MutableStateFlow(false) }

class WidgetEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && !ClockSession.state.value.busy) WidgetRequest.pending.value = true
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}

class AlarmWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val action = PendingIntent.getActivity(context, 0, Intent(context, WidgetEntryActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val views = RemoteViews(context.packageName, R.layout.alarm_widget)
        views.setOnClickPendingIntent(R.id.widget_action, action)
        ids.forEach { manager.updateAppWidget(it, views) }
    }
}
