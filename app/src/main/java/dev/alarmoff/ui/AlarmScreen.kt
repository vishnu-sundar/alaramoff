package dev.alarmoff.ui

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.alarmoff.AlarmViewModel
import dev.alarmoff.clock.ClockSession
import dev.alarmoff.clock.SessionState
import dev.alarmoff.core.*
import java.util.Date
import kotlin.math.*

private val rounded = RoundedCornerShape(20.dp)

@Composable fun AlarmScreen(model: AlarmViewModel, state: SessionState, activity: Activity, widget: Boolean) {
    val colors = MaterialTheme.colorScheme
    val inv = state.inventory
    val ready = model.consent && state.connected && model.clock != null
    var access by remember { mutableStateOf(false) }
    val duplicateActives = inv?.alarms?.any { it.enabled && !inv.unique(it.key) } == true
    fun openClock() {
        val intent = model.clock?.intent ?: return
        runCatching { activity.startActivity(intent) }.onFailure { ClockSession.fail("Clock could not be opened.") }
    }
    fun settings() {
        runCatching { activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { ClockSession.fail("Open Accessibility in Android Settings to manage AlarmOff.") }
    }
    Scaffold(containerColor = colors.background, bottomBar = {
        if (ready && inv != null && inv.alarms.isNotEmpty() && !state.busy) {
            Surface(shadowElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 8.dp)) {
                    if (model.selected.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { model.prepare(inv, true) }, modifier = Modifier.weight(1f),
                            enabled = inv.alarms.any { !it.enabled && it.key in model.selected }) { Text("Enable selected") }
                        OutlinedButton(onClick = { model.prepare(inv, false) }, modifier = Modifier.weight(1f),
                            enabled = inv.alarms.any { it.enabled && it.key in model.selected }) { Text("Disable selected") }
                    }
                    Button({ model.prepare(inv, false, allActive = true) }, Modifier.fillMaxWidth(),
                        enabled = inv.active > 0 && !duplicateActives, shape = RoundedCornerShape(14.dp)) { Text("Disable all alarms") }
                }
            }
        }
    }) { insets ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(insets), horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("AlarmOff", fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton({ access = true }) { Text("Clock access") }
                }
            }
            item { Overview(inv) }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("OnePlus alarms", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
                    if (ready) TextButton({ model.read(activity) }, enabled = !state.busy) { Text("Refresh") }
                }
            }
            if (state.busy) item {
                Column(Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(state.message.orEmpty(), Modifier.padding(vertical = 6.dp))
                    TextButton({ ClockSession.service?.cancel() }) { Text("Stop operation") }
                }
            } else if (!ready) item {
                val title = when {
                    model.clock == null -> "OnePlus Clock unavailable"
                    !model.consent -> "Connect your Clock"
                    else -> "Turn on Clock access"
                }
                val body = when {
                    model.clock == null -> "This device needs a supported, preinstalled OnePlus Clock app."
                    widget -> "Finish setup to review active alarms from your widget."
                    !model.consent -> "Allow AlarmOff to read your alarm list and operate the switches you choose."
                    else -> "In Android Accessibility settings, enable AlarmOff Clock access, then return here."
                }
                Notice(title, body) {
                    if (model.clock != null) Button({ if (!model.consent) access = true else settings() }) {
                        Text(if (!model.consent) "Review access" else "Open settings")
                    }
                }
            } else if (inv == null && !state.failed) item {
                Notice("Read your alarms", "Refresh opens the Alarm tab in Clock. Keep the phone unlocked while AlarmOff reads the list.") {
                    Button({ model.read(activity) }) { Text("Read alarms") }
                }
            }
            if (!state.busy && state.message != null) item {
                Surface(shape = RoundedCornerShape(12.dp), color = if (state.failed) colors.errorContainer else colors.primaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(state.message, style = MaterialTheme.typography.bodyMedium)
                        if (state.failed && model.clock != null) TextButton({ openClock() }) { Text("Open Clock") }
                    }
                }
            }
            if (!state.busy && inv != null && ready) {
                if (inv.alarms.isEmpty()) item { Notice("No alarms set", "Add an alarm in Clock, then refresh this list.") {
                    TextButton({ openClock() }) { Text("Open Clock") }
                } } else {
                    item {
                        Column(Modifier.fillMaxWidth()) {
                            state.readAt?.let { Text("Last read " + DateFormat.getTimeFormat(activity).format(Date(it)),
                                style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${model.selected.size} selected", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                                TextButton({ model.all(inv) }) { Text("Select all") }
                                TextButton({ model.clear() }, enabled = model.selected.isNotEmpty()) { Text("Deselect all") }
                            }
                        }
                    }
                    if (inv.multiplicity.any { it.value > 1 }) item {
                        Text("Matching alarms need distinct labels in Clock before you can select them.", style = MaterialTheme.typography.bodySmall)
                    }
                    items(inv.alarms) { alarm -> AlarmRow(alarm, alarm.key in model.selected, inv.unique(alarm.key)) { model.toggle(alarm.key) } }
                }
            }
        }
    }
    if (access) {
        AlertDialog(onDismissRequest = { access = false }, title = { Text("Clock access") }, text = {
            Text("AlarmOff reads times, labels, repeat days and switch states from the visible OnePlus Clock screen. After you confirm, it presses the matching switches and checks the result. Alarm details stay in memory on this phone. You can disconnect at any time.")
        }, confirmButton = {
            TextButton({
                access = false
                if (!model.consent) model.allow(true)
                settings()
            }) { Text(if (model.consent) "Android settings" else "Agree and open settings") }
        }, dismissButton = {
            TextButton({ access = false; if (model.consent) model.allow(false) }) { Text(if (model.consent) "Disconnect" else "Cancel") }
        })
    }
    model.review?.let { plan ->
        val verb = if (plan.enable) "Enable" else "Disable"
        val count = plan.keys.size
        AlertDialog(onDismissRequest = model::dismiss, title = { Text("$verb $count " + if (count == 1) "alarm?" else "alarms?") },
            text = {
                Column {
                    Text("Clock will open and AlarmOff will check each switch after the change.")
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.heightIn(max = 180.dp)) {
                        items(plan.keys.toList()) { key ->
                            Text(listOf(key.time, key.label, key.repeat).filter(String::isNotBlank).joinToString(" · "),
                                modifier = Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }, confirmButton = { Button({ model.confirm(activity) }) { Text("$verb $count") } },
            dismissButton = { TextButton(model::dismiss) { Text("Cancel") } })
    }
}

@Composable private fun Notice(title: String, body: String, button: @Composable () -> Unit) {
    Surface(shape = rounded, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            button()
        }
    }
}

@Composable private fun Overview(inv: Inventory?) {
    Surface(shape = rounded, color = Color(0xFF183D35), contentColor = Color(0xFFF0F7EF)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 19.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Alarm overview", style = MaterialTheme.typography.labelLarge, color = Color(0xFFC3E4D0))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Metric(inv?.active, "Active")
                    Metric(inv?.let { it.alarms.size - it.active }, "Inactive")
                }
            }
            Canvas(Modifier.size(62.dp)) {
                val r = size.minDimension * .44f
                val ink = Color(0xFFB7EAC7)
                drawCircle(ink.copy(alpha = .4f), r, style = Stroke(width = 1.2.dp.toPx()))
                for (i in 0 until 12) {
                    val a = (i * PI / 6).toFloat()
                    val v = Offset(sin(a), -cos(a))
                    drawLine(ink, center + v * (r - 5.dp.toPx()), center + v * (r - 2.dp.toPx()), 1.3.dp.toPx())
                }
                drawLine(ink, center, center + Offset(-r * .44f, -r * .36f), 2.5.dp.toPx(), StrokeCap.Round)
                drawLine(ink, center, center + Offset(r * .45f, -r * .53f), 2.5.dp.toPx(), StrokeCap.Round)
                drawCircle(ink, 2.dp.toPx())
            }
        }
    }
}

@Composable private fun Metric(value: Int?, label: String) {
    Column {
        Text(value?.toString() ?: "—", fontSize = 33.sp, lineHeight = 37.sp, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color(0xFFC3E4D0))
    }
}

@Composable private fun AlarmRow(alarm: Alarm, selected: Boolean, unique: Boolean, onToggle: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(15.dp), color = if (selected) c.primaryContainer else c.surface) {
        Row(Modifier.fillMaxWidth().toggleable(value = selected, enabled = unique, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = selected, onCheckedChange = null, enabled = unique)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(alarm.key.time, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(alarm.key.label.ifBlank { "Label not shown" }, style = MaterialTheme.typography.bodyMedium)
                Text(alarm.key.repeat.ifBlank { "Repeat not shown" }, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(if (alarm.enabled) c.primary else c.outline, CircleShape))
                    Spacer(Modifier.width(5.dp))
                    Text(if (alarm.enabled) "On" else "Off", style = MaterialTheme.typography.labelLarge)
                }
                if (!unique) Text("Duplicate", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
            }
        }
    }
}
