package dev.alarmoff.fixture;

import android.app.Activity;
import android.appwidget.*;
import android.content.ComponentName;
import android.os.Bundle;
import android.widget.FrameLayout;
import android.widget.TextView;

/** A real Android widget host used solely by the emulator test. */
public final class WidgetHostActivity extends Activity {
    private AppWidgetHost host;
    private int widgetId;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        host = new AppWidgetHost(this, 921);
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        widgetId = host.allocateAppWidgetId();
        ComponentName component = new ComponentName("dev.alarmoff.qa", "dev.alarmoff.AlarmWidget");
        if (!manager.bindAppWidgetIdIfAllowed(widgetId, component)) {
            TextView error = new TextView(this);
            error.setText("Widget binding failed");
            setContentView(error);
            return;
        }
        FrameLayout layout = new FrameLayout(this);
        AppWidgetHostView view = host.createView(this, widgetId, manager.getAppWidgetInfo(widgetId));
        int height = Math.round(100 * getResources().getDisplayMetrics().density);
        layout.addView(view, new FrameLayout.LayoutParams(-1, height));
        setContentView(layout);
    }
    @Override protected void onStart() { super.onStart(); host.startListening(); }
    @Override protected void onStop() { host.stopListening(); super.onStop(); }
    @Override protected void onDestroy() { host.deleteAppWidgetId(widgetId); super.onDestroy(); }
}
