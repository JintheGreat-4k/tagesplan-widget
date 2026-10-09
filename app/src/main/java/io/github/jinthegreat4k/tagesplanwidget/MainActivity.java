package io.github.jinthegreat4k.tagesplanwidget;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONObject;

import java.time.ZonedDateTime;

/** Einrichten: mit dem Google-Skript verbinden, Widgets hinlegen, Akku-Einstellung */
public final class MainActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String neuUrl, neuKey; // aus der Web-App übergeben, noch nicht bestätigt
    private boolean beschaeftigt;

    private TextView status, meldung, verbindenText, akkuText, version;
    private View boxVerbinden, boxEinrichten, boxWidgets;
    private Button btnVerbinden, btnLink, btnAkku;
    private EditText link;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        View root = findViewById(R.id.root);
        // Rand 20dp steht im Layout. Hier nur Statusleiste, Navigationsleiste und Tastatur freihalten (Android 15 zeichnet bis an den Rand).
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return WindowInsets.CONSUMED;
        });
        status = findViewById(R.id.status);
        meldung = findViewById(R.id.meldung);
        verbindenText = findViewById(R.id.verbinden_text);
        akkuText = findViewById(R.id.akku_text);
        version = findViewById(R.id.version);
        boxVerbinden = findViewById(R.id.box_verbinden);
        boxEinrichten = findViewById(R.id.box_einrichten);
        boxWidgets = findViewById(R.id.box_widgets);
        btnVerbinden = findViewById(R.id.btn_verbinden);
        btnLink = findViewById(R.id.btn_link);
        btnAkku = findViewById(R.id.btn_akku);
        link = findViewById(R.id.link);

        btnVerbinden.setOnClickListener(v -> verbinden(neuUrl, neuKey));
        btnLink.setOnClickListener(v -> linkVerbinden());
        findViewById(R.id.btn_kalender).setOnClickListener(v -> anheften(KalenderWidget.class));
        findViewById(R.id.btn_aufgaben).setOnClickListener(v -> anheften(AufgabenWidget.class));
        btnAkku.setOnClickListener(v -> akku());
        findViewById(R.id.btn_jetzt).setOnClickListener(v -> {
            Speicher.laeuft(this, true);
            Darstellung.alle(this);
            Planer.jetzt(this);
            zeigeMeldung("Die Widgets werden aktualisiert.", false);
        });
        findViewById(R.id.btn_trennen).setOnClickListener(v -> trennen());

        uebernehmen(getIntent());
        anzeigen();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        uebernehmen(i);
        anzeigen();
    }

    @Override
    protected void onResume() {
        super.onResume();
        anzeigen();
    }

    /** Adresse und Schlüssel aus dem Link der Web-App (intent://verbinden … S.u=…;S.k=…) */
    private void uebernehmen(Intent i) {
        if (i == null) return;
        String u = i.getStringExtra("u"), k = i.getStringExtra("k");
        if (u == null || k == null) return;
        neuUrl = u.trim();
        neuKey = k.trim();
        i.removeExtra("k");
        meldung.setVisibility(View.GONE);
        if (BuildConfig.DEBUG && i.getBooleanExtra("autotest", false)) verbinden(neuUrl, neuKey);
    }

    private void anzeigen() {
        Konfig k = Konfig.lesen(this);
        if (k != null) {
            String s = Logik.stand(Math.max(Speicher.standAufgaben(this), Speicher.standTermine(this)), ZonedDateTime.now(Logik.TZ));
            status.setText("Verbunden ✓" + (s.isEmpty() ? "" : " · " + s));
        } else {
            status.setText("Noch nicht verbunden");
        }
        boxVerbinden.setVisibility(neuUrl != null ? View.VISIBLE : View.GONE);
        if (neuUrl != null) {
            verbindenText.setText((k != null ? "Das Widget ist schon verbunden. Mit diesem Google-Skript neu verbinden?" : "Widget mit deinem Tagesplan verbinden?")
                    + "\n" + kurzeAdresse(neuUrl));
        }
        boxEinrichten.setVisibility(k == null && neuUrl == null ? View.VISIBLE : View.GONE);
        boxWidgets.setVisibility(k != null ? View.VISIBLE : View.GONE);
        boolean frei = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        akkuText.setText(frei ? "Akku ist nicht eingeschränkt. Die Widgets laden etwa alle 30 Minuten neu, nach Abhaken oder ↻ sofort."
                : "Damit Samsung die App nicht schlafen legt: einmal «Akku nicht einschränken» antippen und «Zulassen» wählen. Sonst laden die Widgets manchmal erst, wenn du ↻ antippst.");
        btnAkku.setVisibility(frei ? View.GONE : View.VISIBLE);
        btnVerbinden.setEnabled(!beschaeftigt);
        btnLink.setEnabled(!beschaeftigt);
        version.setText("Tagesplan Widget · Version " + BuildConfig.VERSION_NAME);
    }

    private void linkVerbinden() {
        String s = link.getText().toString().trim();
        int h = s.indexOf('#');
        String u = null, k = null;
        if (h >= 0) {
            for (String teil : s.substring(h + 1).split("&")) {
                int eq = teil.indexOf('=');
                if (eq < 0) continue;
                String name = teil.substring(0, eq), wert = Uri.decode(teil.substring(eq + 1));
                if (name.equals("u")) u = wert;
                else if (name.equals("k")) k = wert;
            }
        }
        if (u == null || k == null) {
            zeigeMeldung("Das ist nicht der Einrichtungslink. Er enthält «#u=» und «&k=».", true);
            return;
        }
        verbinden(u, k);
    }

    private void verbinden(String u, String key) {
        String grund = Konfig.pruefen(u, key);
        if (grund != null) {
            zeigeMeldung(grund, true);
            return;
        }
        beschaeftigt = true;
        anzeigen();
        zeigeMeldung("Verbinde …", false);
        Context app = getApplicationContext();
        Konfig neu = new Konfig(u, key);
        new Thread(() -> {
            try {
                JSONObject r = Api.post(neu, "load", null);
                Konfig alt = Konfig.lesen(app);
                if (alt == null || !alt.url.equals(neu.url)) Speicher.allesLoeschen(app);
                Konfig.speichern(app, neu);
                Speicher.fehler(app, null);
                Speicher.aufgabenSetzen(app, r.optJSONObject("data"));
                Planer.regelmaessig(app);
                Planer.mitternacht(app);
                Planer.jetzt(app);
                Darstellung.alle(app);
                ui.post(() -> {
                    beschaeftigt = false;
                    neuUrl = null;
                    neuKey = null;
                    link.setText("");
                    zeigeMeldung("Verbunden. Lege jetzt die Widgets auf den Startbildschirm.", false);
                    anzeigen();
                });
            } catch (Api.Fehler e) {
                ui.post(() -> {
                    beschaeftigt = false;
                    zeigeMeldung("Verbinden hat nicht geklappt. " + Api.text(e), true);
                    anzeigen();
                });
            }
        }, "tagesplan-verbinden").start();
    }

    private void anheften(Class<?> cls) {
        AppWidgetManager m = getSystemService(AppWidgetManager.class);
        if (m != null && m.isRequestPinAppWidgetSupported()) {
            m.requestPinAppWidget(new ComponentName(this, cls), null, null);
        } else {
            zeigeMeldung("Lange auf eine freie Stelle des Startbildschirms drücken → Widgets → «Tagesplan Widget».", false);
        }
    }

    private void akku() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        }
    }

    private void trennen() {
        new AlertDialog.Builder(this)
                .setMessage("Verbindung trennen? Die Widgets zeigen danach nichts mehr an, bis du wieder verbindest. Deine Aufgaben und Termine bleiben, wo sie sind.")
                .setPositiveButton("Trennen", (d, w) -> {
                    Konfig.loeschen(this);
                    Speicher.allesLoeschen(this);
                    Planer.stoppen(this);
                    Darstellung.alle(this);
                    zeigeMeldung("Getrennt.", false);
                    anzeigen();
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private void zeigeMeldung(String text, boolean fehler) {
        meldung.setText(text);
        meldung.setTextColor(getColor(fehler ? R.color.spaet : R.color.ink2));
        meldung.setVisibility(View.VISIBLE);
    }

    private static String kurzeAdresse(String u) {
        return u.length() > 60 ? u.substring(0, 40) + "…" + u.substring(u.length() - 12) : u;
    }
}
