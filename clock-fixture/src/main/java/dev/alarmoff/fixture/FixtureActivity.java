package dev.alarmoff.fixture;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.Locale;

/** An isolated emulator test double. This module is never packaged in AlarmOff. */
public final class FixtureActivity extends Activity {
    private SharedPreferences alarms;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        alarms = getSharedPreferences("clock", MODE_PRIVATE);
        if (!alarms.contains("alarm_0")) {
            SharedPreferences.Editor initial = alarms.edit();
            for (int i = 0; i < 14; i++) initial.putBoolean("alarm_" + i, i % 3 != 2);
            initial.commit();
        }
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        TextView heading = text(0, "Alarms", 25);
        heading.setAccessibilityHeading(true);
        layout.addView(heading);
        ListView list = new ListView(this);
        list.setId(R.id.alarm_list);
        list.setAdapter(new Rows());
        layout.addView(list);
        setContentView(layout);
    }

    private TextView text(int id, String value, int size) {
        TextView v = new TextView(this);
        if (id != 0) v.setId(id);
        v.setText(value);
        v.setTextSize(size);
        return v;
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private final class Rows extends BaseAdapter {
        public int getCount() { return 14; }
        public Integer getItem(int i) { return i; }
        public long getItemId(int i) { return i; }
        public boolean hasStableIds() { return true; }
        public View getView(int i, View recycled, ViewGroup parent) {
            LinearLayout row = new LinearLayout(FixtureActivity.this);
            row.setLayoutParams(new AbsListView.LayoutParams(-1, dp(94)));
            row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            row.setPadding(dp(12), 0, dp(12), 0);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout labels = new LinearLayout(FixtureActivity.this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.addView(text(R.id.alarm_time, String.format(Locale.US, "%02d:15", 6 + i), 20));
            labels.addView(text(R.id.alarm_label, "Alarm " + (i + 1), 16));
            labels.addView(text(R.id.alarm_repeat, i % 2 == 0 ? "Weekdays" : "Once", 13));
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
            Switch toggle = new Switch(FixtureActivity.this);
            toggle.setId(R.id.alarm_switch);
            toggle.setChecked(alarms.getBoolean("alarm_" + i, false));
            toggle.setOnCheckedChangeListener((v, checked) -> alarms.edit().putBoolean("alarm_" + i, checked).commit());
            row.addView(toggle);
            return row;
        }
    }
}
