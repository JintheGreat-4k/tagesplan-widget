#!/usr/bin/env bash
# Läuft auf GitHub im Android-Emulator gegen einen nachgebildeten Lifehub (tools/mock_server.py): mit dem Widget-Code
# verbinden, beide Widgets zeigen, abhaken und wieder öffnen, im Lifehub entfernen (401), mit einem neuen Widget-Code neu
# verbinden (auch nochmals mit dem alten Token), «Verbindung trennen» in der App, Bildschirmfotos.
#
# Achtung «am start» auf eine Activity, die schon im Task liegt: Ist sie die Wurzel des Tasks und liegt eine andere
# darüber, holt Android nur den Task nach vorne (gleicher Intent bis auf die Extras) und stellt den Intent nicht zu.
# Deshalb in Schritt 5 die Testseite zuerst mit --activity-clear-top nach oben holen. Liegt die Activity zuoberst
# (singleTop) oder ist sie nicht die Wurzel, kommt der Intent über onNewIntent an (Schritte 1b, 3, 6).
set -euo pipefail
PKG=io.github.jinthegreat4k.tagesplanwidget
# Genau der Link, den der Tagesplan im Lifehub baut (plan/plan.js), mit dem Test-Code des nachgebildeten Lifehub
LINK="intent://code#Intent;scheme=tagesplanwidget;package=$PKG;S.c=TEST-2345;S.browser_fallback_url=https%3A%2F%2Fgithub.com%2FJintheGreat-4k%2Ftagesplan-widget%23installieren;end"
mkdir -p bilder
foto() { adb exec-out screencap -p > "bilder/$1.png"; echo "Foto: $1"; }

python3 tools/mock_server.py 8080 > bilder/mock.log 2>&1 &
MOCK=$!
trap 'kill $MOCK 2>/dev/null || true; adb logcat -d -s Tagesplan:V AndroidRuntime:E > bilder/logcat.txt 2>/dev/null || true' EXIT
sleep 2

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell settings put system time_12_24 24 || true
adb shell appwidget grantbind --package "$PKG" --user 0 || echo "grantbind nicht verfügbar, Testseite zeichnet direkt"
adb logcat -c || true

# 1a. Der Link selbst, ohne -n (am wertet ihn mit Intent.parseUri aus wie Chrome): Die App öffnet sich mit dem
#     Widget-Code und fragt «Mit dem Lifehub verbinden?».
adb shell "am start -W '$LINK'"
sleep 4
foto 00-link-bestaetigen
PROTOKOLL=$(adb logcat -d -s Tagesplan:V 2>&1 || true)
if ! grep -q "Widget-Code aus dem Link erhalten" <<< "$PROTOKOLL"; then
  echo "FEHLER: Der Link aus dem Lifehub öffnet die App nicht mit dem Widget-Code"; exit 1
fi
# 1b. Verbinden. Nur die Test-Version nimmt basis (nachgebildeter Lifehub) und autotest («Verbinden» antippen) an.
adb shell am start -W -a android.intent.action.VIEW -d "tagesplanwidget://code" -n "$PKG/.MainActivity" \
  --es c TEST-2345 --es basis http://10.0.2.2:8080 --ez autotest true
sleep 8
foto 01-einrichtung

# 2. Beide Widgets wie auf dem Startbildschirm
adb shell am start -W -n "$PKG/.TestHostActivity"
sleep 12
foto 02-widgets-hell

# 3. Erste Aufgabe abhaken, dann denselben Haken nochmals: wieder öffnen (die abgehakte bleibt durchgestrichen stehen)
adb shell am start -W -n "$PKG/.TestHostActivity" --es klick abhaken
sleep 15
foto 03-abgehakt
adb shell am start -W -n "$PKG/.TestHostActivity" --es klick abhaken
sleep 15
foto 04-wieder-offen

curl -s http://127.0.0.1:8080/test/log > bilder/anfragen.json
python3 tools/pruefen.py bilder/anfragen.json

# 4. Dunkles Design
adb shell cmd uimode night yes
sleep 3
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/.TestHostActivity"
sleep 10
foto 05-widgets-dunkel
adb shell am start -W -n "$PKG/.MainActivity"
sleep 4
foto 06-einrichtung-dunkel
adb shell cmd uimode night no

# 5. Im Lifehub entfernt: Der nachgebildete Lifehub vergisst den Token, ↻ im Widget bekommt 401.
#    Erwartet: genau ein Aufruf, danach Token, Aufgaben und Termine weg, Widgets und App zeigen «Getrennt».
#    Die Testseite ist seit Schritt 4 die Wurzel des Tasks, die Einrichtung liegt darüber: zuerst die Testseite nach oben
#    holen (beendet die Einrichtung; die Testseite baut sich nach dem Wechsel zurück zu hell neu auf), dann antippen.
curl -s -X POST -H "Content-Type: application/json" -d '{}' http://127.0.0.1:8080/test/entfernen
adb shell am start -W --activity-clear-top -n "$PKG/.TestHostActivity"
sleep 8
adb shell am start -W -n "$PKG/.TestHostActivity" --es klick aktualisieren
sleep 12
PROTOKOLL=$(adb logcat -d -s Tagesplan:V 2>&1 || true)
if ! grep -q "Test: tippe an: aktualisieren" <<< "$PROTOKOLL"; then
  echo "FEHLER: ↻ im Widget wurde nicht angetippt (Intent nicht zugestellt?)"; exit 1
fi
foto 07-widgets-getrennt
adb shell am start -W -n "$PKG/.MainActivity"
sleep 4
foto 08-einrichtung-getrennt

curl -s http://127.0.0.1:8080/test/log > bilder/anfragen-getrennt.json
python3 tools/pruefen.py --getrennt bilder/anfragen-getrennt.json
# Nur die Test-Version lässt run-as zu: In den privaten Daten steht kein Token mehr, die Daten sind gelöscht
KONFIG=$(adb shell run-as "$PKG" cat shared_prefs/konfig.xml 2>&1 || true)
DATEIEN=$(adb shell run-as "$PKG" ls files 2>&1 || true)
echo "konfig.xml: $KONFIG"
echo "files: $DATEIEN"
if ! grep -q 'name="getrennt" value="true"' <<< "$KONFIG"; then echo "FEHLER: «getrennt» nicht gespeichert"; exit 1; fi
if grep -q 'name="token"' <<< "$KONFIG"; then echo "FEHLER: Token nach dem Entfernen noch gespeichert"; exit 1; fi
if grep -q -E 'aufgaben\.json|termine\.json' <<< "$DATEIEN"; then echo "FEHLER: Aufgaben oder Termine nach dem Entfernen noch gespeichert"; exit 1; fi
echo "Getrennt: in Ordnung."

# 6. Neuer Widget-Code im Lifehub: neu verbinden (ohne alten Token), dann mit noch einem Code nochmals (mit dem alten
#    Token: der Lifehub ersetzt die Zeile), dann «Verbindung trennen» in der App (Test-Version: Extra autotrennen statt
#    der Rückfrage, sonst derselbe Ablauf). Die Einrichtung liegt zuoberst (Schritt 5): Die Intents kommen über
#    onNewIntent an. Erwartet: genau ein /api/widget/disconnect mit dem Token und {}, danach keine Anfrage mehr; Token,
#    Aufgaben und Termine weg, «getrennt» nicht gesetzt (App und Widgets zeigen «Noch nicht verbunden»).
#    Statt fester Wartezeiten fragt der Test das Protokoll des nachgebildeten Lifehub ab (diese Abfragen lässt pruefen.py aus).
protokoll_hat() { # Python-Bedingung über «log»; wartet höchstens 30 Sekunden darauf
  local i
  for i in $(seq 1 30); do
    if curl -s http://127.0.0.1:8080/test/log | python3 -c "import json, sys; log = json.load(sys.stdin); sys.exit(0 if ($1) else 1)"; then
      return 0
    fi
    sleep 1
  done
  return 1
}
# Nach dem n-ten gelungenen Verbinden hat der Abgleich «termine» bekommen (das ist seine letzte Anfrage)
abgeglichen() {
  local v="[i for i, e in enumerate(log) if e.get('weg') == '/api/widget/connect' and e.get('status') == 200]"
  echo "len($v) >= $1 and any((e.get('anfrage') or {}).get('action') == 'termine' and e.get('status') == 200 for e in log[$v[$1 - 1] + 1:])"
}
curl -s -X POST -H "Content-Type: application/json" -d '{"code":"TEST-6789"}' http://127.0.0.1:8080/test/code
adb shell am start -W -a android.intent.action.VIEW -d "tagesplanwidget://code" -n "$PKG/.MainActivity" \
  --es c TEST-6789 --es basis http://10.0.2.2:8080 --ez autotest true
if ! protokoll_hat "$(abgeglichen 2)"; then echo "FEHLER: nach dem neuen Widget-Code nicht verbunden und geladen"; exit 1; fi
sleep 2
curl -s -X POST -H "Content-Type: application/json" -d '{"code":"TEST-7892"}' http://127.0.0.1:8080/test/code
adb shell am start -W -a android.intent.action.VIEW -d "tagesplanwidget://code" -n "$PKG/.MainActivity" \
  --es c TEST-7892 --es basis http://10.0.2.2:8080 --ez autotest true
if ! protokoll_hat "$(abgeglichen 3)"; then echo "FEHLER: beim nochmaligen Verbinden nicht verbunden und geladen"; exit 1; fi
sleep 2
foto 09-neu-verbunden
DATEIEN=$(adb shell run-as "$PKG" ls files 2>&1 || true)
echo "files vor dem Trennen: $DATEIEN"
if ! grep -q 'aufgaben\.json' <<< "$DATEIEN"; then echo "FEHLER: nach dem neuen Verbinden keine Aufgaben gespeichert"; exit 1; fi
adb shell am start -W -n "$PKG/.MainActivity" --ez autotrennen true
if ! protokoll_hat "any(e.get('weg') == '/api/widget/disconnect' for e in log)"; then
  echo "FEHLER: «Verbindung trennen» hat den Lifehub nicht erreicht"; exit 1
fi
sleep 5 # fällt danach noch eine Anfrage an, findet sie pruefen.py
foto 10-einrichtung-getrennt-app

curl -s http://127.0.0.1:8080/test/log > bilder/anfragen-trennen.json
python3 tools/pruefen.py --getrennt --trennen bilder/anfragen-trennen.json
KONFIG=$(adb shell run-as "$PKG" cat shared_prefs/konfig.xml 2>&1 || true)
DATEIEN=$(adb shell run-as "$PKG" ls files 2>&1 || true)
echo "konfig.xml: $KONFIG"
echo "files: $DATEIEN"
if grep -q 'name="token"' <<< "$KONFIG"; then echo "FEHLER: Token nach dem Trennen noch gespeichert"; exit 1; fi
if grep -q 'name="getrennt" value="true"' <<< "$KONFIG"; then echo "FEHLER: Nach dem Trennen in der App steht «getrennt» (das heisst «im Lifehub entfernt»)"; exit 1; fi
if grep -q -E 'aufgaben\.json|termine\.json' <<< "$DATEIEN"; then echo "FEHLER: Aufgaben oder Termine nach dem Trennen noch gespeichert"; exit 1; fi
echo "Trennen in der App: in Ordnung."
