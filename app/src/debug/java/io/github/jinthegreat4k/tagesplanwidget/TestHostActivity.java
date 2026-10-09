package io.github.jinthegreat4k.tagesplanwidget;

import android.app.Activity;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Insets;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.SizeF;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.RemoteViews;

import java.util.List;

/**
 * Nur für den Emulator-Test: zeigt beide Widgets wie ein Startbildschirm (eigener Widget-Host).
 * Mit dem Extra «klick=abhaken» tippt sie den ersten Haken im Aufgaben-Widget an.
 */
public final class TestHostActivity extends Activity {
    private static final int HOST_ID = 4711;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private AppWidgetHost host;
    private LinearLayout box;
    private boolean direkt; // Binden nicht erlaubt: Widgets selbst zeichnen und regelmässig erneuern

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xFF5D7480); // wie ein Hintergrundbild, damit man die Widget-Ränder sieht
        box.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets i = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(i.left + dp(12), i.top + dp(12), i.right + dp(12), i.bottom + dp(12));
            return WindowInsets.CONSUMED;
        });
        setContentView(box);
        host = new AppWidgetHost(this, HOST_ID);
        host.deleteHost();
        AppWidgetManager m = AppWidgetManager.getInstance(this);
        zeigen(m, KalenderWidget.class);
        zeigen(m, AufgabenWidget.class);
        if (direkt) erneuern();
        klickSpaeter(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        klickSpaeter(i);
    }

    @Override
    protected void onStart() {
        super.onStart();
        host.startListening();
    }

    @Override
    protected void onStop() {
        super.onStop();
        host.stopListening();
    }

    private void zeigen(AppWidgetManager m, Class<?> cls) {
        ComponentName cn = new ComponentName(this, cls);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(330));
        lp.bottomMargin = dp(12);
        int id = host.allocateAppWidgetId();
        if (m.bindAppWidgetIdIfAllowed(id, cn)) {
            AppWidgetProviderInfo info = m.getAppWidgetInfo(id);
            AppWidgetHostView v = host.createView(this, id, info);
            v.updateAppWidgetSize(new Bundle(), List.of(new SizeF(380, 330)));
            box.addView(v, lp);
            // Wie ein Launcher nach dem Hinlegen: Widget zeichnen lassen
            sendBroadcast(new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setComponent(cn)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, new int[]{id}));
            Log.i(Speicher.TAG, "Test: Widget gebunden " + cls.getSimpleName());
        } else {
            host.deleteAppWidgetId(id);
            direkt = true;
            box.addView(new LinearLayout(this), lp);
            Log.i(Speicher.TAG, "Test: Binden nicht erlaubt, zeichne direkt " + cls.getSimpleName());
        }
    }

    private void erneuern() {
        if (isDestroyed()) return;
        for (int i = 0; i < 2; i++) {
            ViewGroup platz = (ViewGroup) box.getChildAt(i);
            RemoteViews rv = i == 0 ? Darstellung.bauenKalender(this) : Darstellung.bauenAufgaben(this);
            platz.removeAllViews();
            platz.addView(rv.apply(this, platz), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        ui.postDelayed(this::erneuern, 2000);
    }

    private void klickSpaeter(Intent i) {
        if (i == null || !"abhaken".equals(i.getStringExtra("klick"))) return;
        ui.postDelayed(() -> {
            View v = finde(box, R.id.haken);
            if (v != null) {
                Log.i(Speicher.TAG, "Test: tippe ersten Haken an");
                v.performClick();
            } else {
                Log.w(Speicher.TAG, "Test: kein Haken gefunden");
            }
        }, 1500);
    }

    private static View finde(View v, int id) {
        if (v.getId() == id && v.isShown()) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = finde(g.getChildAt(i), id);
                if (r != null) return r;
            }
        }
        return null;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
