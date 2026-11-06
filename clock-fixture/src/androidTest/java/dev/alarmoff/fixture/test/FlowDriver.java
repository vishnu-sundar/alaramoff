package dev.alarmoff.fixture.test;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Uses a real foreground accessibility tree, without suspending AlarmOff's service. */
public final class FlowDriver extends Instrumentation {
    private UiAutomation ui;
    private static final String APP = "dev.alarmoff.qa/dev.alarmoff.MainActivity";
    private static final String FIX = "dev.alarmoff.fixture/";
    private final List<String> steps = new ArrayList<>();
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            ui = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            shell("am start -n " + FIX + ".FixtureActivity");
            waitFor("Alarms", false);
            assertActive(10);
            shell("am start -n " + APP);
            click("Review access");
            click("Agree and open settings");
            shell("settings put secure enabled_accessibility_services dev.alarmoff.qa/dev.alarmoff.clock.ClockService");
            shell("settings put secure accessibility_enabled 1");
            shell("am start -n " + APP);
            click("Read alarms");
            waitFor("Refreshed 14 alarm(s).", false);
            assertActive(10);
            step("Read 14 real fixture switches across multiple screens");
            clickPrefix("06:15");
            click("Disable selected");
            click("Cancel");
            assertActive(10);
            step("Cancelling the review leaves every alarm unchanged");
            click("Disable selected");
            click("Disable 1");
            waitFor("Verified 1 alarm(s) disabled.", false);
            assertActive(9);
            step("Selected disable changed only the first alarm");
            click("Select all");
            click("Deselect all");
            waitFor("0 selected", false);
            click("Select all");
            click("Enable selected");
            click("Enable 5");
            waitFor("Verified 5 alarm(s) enabled.", false);
            assertActive(14);
            step("Select all and Enable selected restored the five inactive alarms");
            click("Disable all alarms");
            waitFor("Disable 14 alarms?", false);
            click("Cancel");
            assertActive(14);
            shell("am start -n " + FIX + ".WidgetHostActivity");
            click("Disable all alarms");
            waitFor("Disable 14 alarms?", false);
            assertActive(14);
            step("The real home-screen widget requested a fresh review without changing a switch");
            click("Disable 14");
            waitFor("Verified 14 alarm(s) disabled.", false);
            assertActive(0);
            step("Widget confirmation disabled every alarm, including off-screen rows");
            result.putString("stream", String.join("\n", steps) + "\nALARM_OFF_PASS\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable t) {
            result.putString("stream", String.join("\n", steps) + "\nFAIL: " + t + "\nVisible: " + visible());
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void step(String text) {
        steps.add(text);
        Bundle b = new Bundle();
        b.putString("stream", text + "\n");
        sendStatus(0, b);
    }
    private SharedPreferences clock() { return getTargetContext().getSharedPreferences("clock", Context.MODE_PRIVATE); }
    private void assertActive(int expected) {
        int active = 0;
        for (int i = 0; i < 14; i++) {
            if (!clock().contains("alarm_" + i)) throw new AssertionError("Missing fixture alarm " + i);
            if (clock().getBoolean("alarm_" + i, false)) active++;
        }
        if (active != expected) throw new AssertionError("Expected " + expected + " active switches, found " + active);
    }
    private void shell(String cmd) throws Exception {
        try (ParcelFileDescriptor p = ui.executeShellCommand(cmd);
             InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(p)) {
            byte[] buffer = new byte[2048];
            while (in.read(buffer) != -1) { /* Await the shell operation. */ }
        }
    }
    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String wanted, boolean prefix) {
        if (node == null) return null;
        for (CharSequence c : new CharSequence[]{node.getText(), node.getContentDescription()}) {
            if (c != null && (prefix ? c.toString().startsWith(wanted) : c.toString().equals(wanted))) return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo hit = find(node.getChild(i), wanted, prefix);
            if (hit != null) return hit;
        }
        return null;
    }
    private AccessibilityNodeInfo waitFor(String wanted, boolean prefix) {
        long end = SystemClock.elapsedRealtime() + 65000;
        do {
            AccessibilityNodeInfo result = find(ui.getRootInActiveWindow(), wanted, prefix);
            if (result != null) return result;
            SystemClock.sleep(160);
        } while (SystemClock.elapsedRealtime() < end);
        throw new AssertionError("Could not find " + wanted);
    }
    private void click(String text) { press(waitFor(text, false)); }
    private void clickPrefix(String text) { press(waitFor(text, true)); }
    private void press(AccessibilityNodeInfo n) {
        while (n != null && !n.isClickable()) n = n.getParent();
        if (n == null || !n.isEnabled() || !n.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            throw new AssertionError("Control did not accept click");
        SystemClock.sleep(250);
    }
    private String visible() {
        StringBuilder out = new StringBuilder();
        if (ui != null) describe(ui.getRootInActiveWindow(), out);
        return out.toString();
    }
    private void describe(AccessibilityNodeInfo n, StringBuilder out) {
        if (n == null || out.length() > 5000) return;
        out.append(n.getClassName()).append("[").append(n.getText()).append(" | ").append(n.getContentDescription()).append("] ");
        for (int i = 0; i < n.getChildCount(); i++) describe(n.getChild(i), out);
    }
}
