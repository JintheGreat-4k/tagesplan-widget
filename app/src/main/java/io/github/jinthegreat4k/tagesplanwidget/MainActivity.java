package io.github.jinthegreat4k.tagesplanwidget;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.time.ZonedDateTime;

/** Einrichten: mit dem Lifehub verbinden (Widget-Code), Widgets hinlegen, Akku-Einstellung */
public final class MainActivity extends Activity {
    // Verbunden, aber so lange nicht mehr abgeglichen: beim Öffnen gleich nachsehen
    private static final long AUFFRISCHEN_MS = 2 * 60_000L;
    // Verbinden läuft im Hintergrund und überlebt das Neuaufbauen der Seite (Drehen, dunkles Design, geteilter
    // Bildschirm): Solange es läuft, sind die Knöpfe gesperrt; das Ergebnis zeigt die Seite, die dann sichtbar ist.
    // Nur im Haupt-Thread gelesen und geschrieben.
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static String verbindetMit;   // Widget-Code, mit dem gerade verbunden wird, sonst null
    private static Ergebnis ergebnis;     // Ergebnis des letzten Verbindens, noch nicht gezeigt
    private static MainActivity sichtbar; // die Seite zwischen onResume und onPause

    // Verbindung oder Stand haben sich geändert (auch durch den Abgleich im Hintergrund): neu zeigen
    private final SharedPreferences.OnSharedPreferenceChangeListener geaendert = (p, name) -> anzeigen();
    private String neuCode;  // Widget-Code aus dem Link des Lifehub, noch nicht bestätigt
    private String neuBasis; // nur in der Test-Version: nachgebildeter Lifehub auf dem Rechner

    private TextView status, meldung, verbindenText, codeText, getrenntText, einrichtenTitel, einrichtenText, akkuText, version;
    private View boxVerbinden, boxEinrichten, boxWidgets;
    private Button btnVerbinden, btnAbbrechen, btnCode, btnAkku, btnTrennen;
    private EditText code;

    /** Was beim Verbinden herauskam */
    private static final class Ergebnis {
        final String code; // mit diesem Widget-Code
        final boolean ok;
        final boolean falscherCode;
        final String text;

        Ergebnis(String code, boolean ok, boolean falscherCode, String text) {
            this.code = code;
            this.ok = ok;
            this.falscherCode = falscherCode;
            this.text = text;
        }
    }

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
        codeText = findViewById(R.id.code_text);
        getrenntText = findViewById(R.id.getrennt_text);
        einrichtenTitel = findViewById(R.id.einrichten_titel);
        einrichtenText = findViewById(R.id.einrichten_text);
        akkuText = findViewById(R.id.akku_text);
        version = findViewById(R.id.version);
        boxVerbinden = findViewById(R.id.box_verbinden);
        boxEinrichten = findViewById(R.id.box_einrichten);
        boxWidgets = findViewById(R.id.box_widgets);
        btnVerbinden = findViewById(R.id.btn_verbinden);
        btnAbbrechen = findViewById(R.id.btn_abbrechen);
        btnCode = findViewById(R.id.btn_code);
        btnAkku = findViewById(R.id.btn_akku);
        btnTrennen = findViewById(R.id.btn_trennen);
        code = findViewById(R.id.code);
        getrenntText.setText(Api.GETRENNT);

        btnVerbinden.setOnClickListener(v -> verbinden(neuCode));
        btnAbbrechen.setOnClickListener(v -> {
            neuCode = null;
            neuBasis = null;
            anzeigen();
        });
        btnCode.setOnClickListener(v -> codeVerbinden());
        code.setOnEditorActionListener((v, aktion, ereignis) -> {
            if (aktion != EditorInfo.IME_ACTION_GO) return false;
            codeVerbinden();
            return true;
        });
        findViewById(R.id.btn_kalender).setOnClickListener(v -> anheften(KalenderWidget.class));
        findViewById(R.id.btn_aufgaben).setOnClickListener(v -> anheften(AufgabenWidget.class));
        btnAkku.setOnClickListener(v -> akku());
        findViewById(R.id.btn_jetzt).setOnClickListener(v -> {
            Speicher.laeuft(this, true);
            Darstellung.alle(this);
            Planer.jetzt(this);
            zeigeMeldung("Die Widgets werden aktualisiert.", false);
        });
        btnTrennen.setOnClickListener(v -> trennen());

        if (b != null) {
            // Neu aufgebaut (z. B. gedreht): den Link nicht nochmals lesen, die offene Frage behalten
            neuCode = b.getString("neuCode");
            neuBasis = b.getString("neuBasis");
        } else {
            uebernehmen(getIntent());
        }
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
        sichtbar = this;
        Konfig.beobachten(this, geaendert, true);
        Speicher.beobachten(this, geaendert, true);
        auffrischen();
        anzeigen();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sichtbar == this) sichtbar = null;
        Konfig.beobachten(this, geaendert, false);
        Speicher.beobachten(this, geaendert, false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Ganz geschlossen (nicht nur neu aufgebaut): ein altes Ergebnis beim nächsten Öffnen nicht mehr zeigen
        if (isFinishing()) ergebnis = null;
    }

    @Override
    protected void onSaveInstanceState(Bundle b) {
        super.onSaveInstanceState(b);
        b.putString("neuCode", neuCode);
        b.putString("neuBasis", neuBasis);
    }

    /** Widget-Code aus dem Link des Lifehub (intent://code … S.c=ABCD-EFGH …). Nur ein gut geformter Code fragt nach. */
    private void uebernehmen(Intent i) {
        if (i == null) return;
        // Aus «Zuletzt verwendet» wieder geöffnet: Dieser Code ist längst benutzt oder abgelaufen
        boolean ausVerlauf = (i.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0;
        // Nur die Test-Version (Emulator auf GitHub): «Verbindung trennen» ohne die Rückfrage
        if (BuildConfig.DEBUG && i.getBooleanExtra("autotrennen", false)) {
            i.removeExtra("autotrennen");
            if (!ausVerlauf) trennenJetzt();
            return;
        }
        if (!i.hasExtra("c")) return;
        String roh = i.getStringExtra("c"), basis = i.getStringExtra("basis");
        boolean auto = BuildConfig.DEBUG && i.getBooleanExtra("autotest", false);
        i.removeExtra("c");
        i.removeExtra("basis");
        i.removeExtra("autotest");
        if (ausVerlauf) return;
        String c = Api.codeNormal(roh);
        if (c == null) {
            Log.w(Speicher.TAG, "Link aus dem Lifehub ohne gültigen Widget-Code");
            neuCode = null;
            zeigeMeldung("Der Link aus dem Lifehub war unvollständig. Den Widget-Code unten eintippen.", true);
            return;
        }
        if (Konfig.codeBenutzt(this, c)) return;
        Log.i(Speicher.TAG, "Widget-Code aus dem Link erhalten"); // nie den Code selbst
        neuCode = c;
        neuBasis = BuildConfig.DEBUG && Api.basisErlaubt(basis, true) ? basis : null;
        meldung.setVisibility(View.GONE);
        if (auto) verbinden(c);
    }

    private void anzeigen() {
        boolean beschaeftigt = verbindetMit != null;
        Ergebnis e = ergebnis;
        if (e != null && !beschaeftigt) {
            // Verbinden ist fertig (vielleicht noch auf der Seite vor dem Drehen angetippt): hier zeigen, einmal
            ergebnis = null;
            if (e.code.equals(neuCode) && (e.ok || e.falscherCode)) {
                // Verbunden, oder dieser Code taugt nicht mehr: statt nochmals «Verbinden» das Feld zeigen
                neuCode = null;
                neuBasis = null;
            }
            if (e.ok) code.setText("");
            zeigeMeldung(e.text, !e.ok);
        } else if (beschaeftigt) {
            zeigeMeldung("Verbinde …", false);
        }
        // Inzwischen mit genau diesem Code verbunden: nicht nochmals fragen
        if (neuCode != null && !beschaeftigt && Konfig.codeBenutzt(this, neuCode)) neuCode = null;
        Konfig k = Konfig.lesen(this);
        if (k != null) {
            String s = Logik.stand(Math.max(Speicher.standAufgaben(this), Speicher.standTermine(this)), ZonedDateTime.now(Logik.TZ));
            String f = Speicher.fehler(this);
            // Ging der letzte Abgleich schief, steht das hier statt «Verbunden ✓»
            if (f != null) status.setText("Letzter Abgleich: " + Api.wort(f) + (s.isEmpty() ? "" : " · " + s));
            else status.setText("Verbunden ✓" + (s.isEmpty() ? "" : " · " + s));
        } else {
            status.setText(Konfig.getrennt(this) ? "Getrennt" : "Noch nicht verbunden");
        }
        boxVerbinden.setVisibility(neuCode != null ? View.VISIBLE : View.GONE);
        if (neuCode != null) {
            verbindenText.setText(k != null ? "Die Widget-App ist schon verbunden. Neu verbinden?" : "Mit dem Lifehub verbinden?");
            codeText.setText("Widget-Code " + Api.codeZeigen(neuCode));
        }
        // Feld für den Widget-Code immer (ausser während der Frage aus dem Link), auch wenn verbunden: Die App merkt
        // erst beim nächsten Abgleich, dass der Lifehub die Verbindung entfernt hat (neues Passwort, überall abgemeldet).
        boxEinrichten.setVisibility(neuCode == null ? View.VISIBLE : View.GONE);
        einrichtenTitel.setText(k != null ? "Mit neuem Widget-Code verbinden" : "Mit dem Lifehub verbinden");
        einrichtenText.setText("Im Lifehub auf dem Handy: Tagesplan → Zahnrad → «Widget-App verbinden» → «Widget-App öffnen». "
                + "Diese App fragt dann «" + (k != null ? "Neu verbinden?" : "Mit dem Lifehub verbinden?") + "»: «Verbinden» antippen. "
                + "Oder hier den Widget-Code eintippen und «Mit Code verbinden» antippen.");
        getrenntText.setVisibility(k == null && Konfig.getrennt(this) ? View.VISIBLE : View.GONE);
        boxWidgets.setVisibility(k != null ? View.VISIBLE : View.GONE);
        boolean frei = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        akkuText.setText(frei ? "Akku ist nicht eingeschränkt. Die Widgets laden etwa alle 30 Minuten neu, nach Abhaken oder ↻ sofort."
                : "Damit Samsung die App nicht schlafen legt: einmal «Akku nicht einschränken» antippen und «Zulassen» wählen. Sonst laden die Widgets manchmal erst, wenn du ↻ antippst.");
        btnAkku.setVisibility(frei ? View.GONE : View.VISIBLE);
        btnVerbinden.setEnabled(!beschaeftigt);
        btnAbbrechen.setEnabled(!beschaeftigt);
        btnCode.setEnabled(!beschaeftigt);
        btnTrennen.setEnabled(!beschaeftigt);
        version.setText("Tagesplan Widget · Version " + BuildConfig.VERSION_NAME);
    }

    private void codeVerbinden() {
        String c = Api.codeNormal(code.getText().toString());
        if (c == null) {
            zeigeMeldung("Der Widget-Code hat 8 Zeichen, z. B. ABCD-EFGH. Du bekommst ihn im Lifehub: Tagesplan → Zahnrad → «Widget-App verbinden».", true);
            return;
        }
        verbinden(c);
    }

    /**
     * Verbunden, aber der letzte Abgleich ist über 2 Minuten her: gleich nachsehen. Hat der Lifehub die Verbindung
     * inzwischen entfernt, zeigt die Seite so bald «Getrennt» statt «Verbunden ✓».
     */
    private void auffrischen() {
        if (verbindetMit != null || Speicher.laeuft(this) || Konfig.lesen(this) == null) return;
        long stand = Math.max(Speicher.standAufgaben(this), Speicher.standTermine(this));
        if (System.currentTimeMillis() - stand > AUFFRISCHEN_MS) Planer.jetzt(this);
    }

    private void verbinden(String c) {
        if (c == null || verbindetMit != null) return;
        verbindetMit = c;
        ergebnis = null;
        anzeigen(); // «Verbinde …», Knöpfe gesperrt
        Context app = getApplicationContext();
        String basis = neuBasis != null ? neuBasis : Api.BASIS;
        Konfig alt = Konfig.lesen(app);
        // Schon verbunden: Der Lifehub ersetzt die alte Verbindung, statt eine zweite Zeile anzulegen
        String alterToken = alt != null && alt.basis.equals(basis) ? alt.token : null;
        String name = Api.name(geraetName());
        new Thread(() -> {
            Ergebnis r;
            try {
                String token = Api.verbinden(basis, c, name, alterToken);
                // Der Code ist jetzt verbraucht: Token sofort speichern. Scheitert das erste Laden, bleibt die Verbindung.
                Konfig.speichern(app, new Konfig(token, basis));
                Konfig.codeMerken(app, c);
                Speicher.allesLoeschen(app); // Reste von 1.1 oder der alten Verbindung (Stand, Fehler, wartendes Abhaken)
                Planer.regelmaessig(app);
                Planer.mitternacht(app);
                Planer.jetzt(app);
                Darstellung.alle(app);
                boolean widgets = Planer.widgetsDa(app);
                r = new Ergebnis(c, true, false,
                        "Verbunden ✓. Die Widgets laden jetzt." + (widgets ? "" : " Lege jetzt die Widgets auf den Startbildschirm."));
            } catch (Api.Fehler x) {
                r = new Ergebnis(c, false, "wrong_code".equals(x.code), "Verbinden hat nicht geklappt. " + Api.text(x));
            }
            Ergebnis fertig = r;
            UI.post(() -> verbunden(fertig));
        }, "tagesplan-verbinden").start();
    }

    /** Im Haupt-Thread: Verbinden ist fertig. Die sichtbare Seite zeigt das Ergebnis, sonst die nächste, die sich öffnet. */
    private static void verbunden(Ergebnis r) {
        verbindetMit = null;
        ergebnis = r;
        if (sichtbar != null) sichtbar.anzeigen();
    }

    /** Name des Handys aus den Einstellungen (z. B. «Galaxy S24»), braucht keine Berechtigung */
    private String geraetName() {
        try {
            return Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME);
        } catch (RuntimeException e) {
            return null;
        }
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
                .setMessage("Verbindung trennen? Die Widgets zeigen danach nichts mehr an, bis du mit einem neuen Widget-Code verbindest. Deine Aufgaben und Termine bleiben im Lifehub.")
                .setPositiveButton("Trennen", (d, w) -> trennenJetzt())
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    /**
     * Im Lifehub austragen, wenn es geht (sonst bleibt die Zeile dort und lässt sich entfernen), hier alles vergessen.
     * Die Widgets zeigen danach «Noch nicht verbunden», nicht «Getrennt»: Das heisst «im Lifehub entfernt».
     */
    private void trennenJetzt() {
        Konfig k = Konfig.lesen(this);
        if (k != null) new Thread(() -> Api.trennen(k), "tagesplan-trennen").start();
        Konfig.loeschen(this);
        Speicher.allesLoeschen(this);
        Planer.stoppen(this);
        Darstellung.alle(this);
        zeigeMeldung("Verbindung getrennt.", false);
        anzeigen();
    }

    private void zeigeMeldung(String text, boolean fehler) {
        meldung.setText(text);
        meldung.setTextColor(getColor(fehler ? R.color.spaet : R.color.ink2));
        meldung.setVisibility(View.VISIBLE);
    }
}
