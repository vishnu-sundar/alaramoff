package dev.alarmoff.clock

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.AlarmClock
import dev.alarmoff.R

data class ClockTarget(val pkg: String, val intent: Intent) {
    companion object {
        fun detect(context: Context): ClockTarget? {
            val pm = context.packageManager
            val candidates = context.getString(R.string.clock_packages).split(',').map(String::trim)
            for (pkg in candidates) {
                val info = try { pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0)) }
                    catch (_: PackageManager.NameNotFoundException) { continue }
                val fixture = context.packageName == "dev.alarmoff.qa" && pkg == "dev.alarmoff.fixture"
                if (!info.enabled || (!fixture && info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0)) continue
                val show = Intent(AlarmClock.ACTION_SHOW_ALARMS).setPackage(pkg)
                val resolved = pm.resolveActivity(show, PackageManager.MATCH_DEFAULT_ONLY)
                val activity = resolved?.activityInfo
                val launch = if (activity?.exported == true && activity.packageName == pkg &&
                    (activity.permission == null || context.checkSelfPermission(activity.permission) == PackageManager.PERMISSION_GRANTED))
                    show.setClassName(pkg, activity.name)
                else pm.getLaunchIntentForPackage(pkg) ?: continue
                return ClockTarget(pkg, launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return null
        }
    }
}
