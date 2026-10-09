#!/usr/bin/env bash
# Läuft auf GitHub im Android-Emulator: verbinden, beide Widgets zeigen, abhaken, Bildschirmfotos.
set -euo pipefail
PKG=io.github.jinthegreat4k.tagesplanwidget
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

# 1. Verbinden wie aus der Web-App (intent://verbinden … S.u=…;S.k=…)
adb shell am start -W -a android.intent.action.VIEW -d "tagesplanwidget://verbinden" -n "$PKG/.MainActivity" \
  --es u "http://10.0.2.2:8080/macros/s/test/exec" --es k "testschluessel-1234567890abcdef0" --ez autotest true
sleep 8
foto 01-einrichtung

# 2. Beide Widgets wie auf dem Startbildschirm
adb shell am start -W -n "$PKG/.TestHostActivity"
sleep 12
foto 02-widgets-hell

# 3. Erste Aufgabe abhaken
adb shell am start -W -n "$PKG/.TestHostActivity" --es klick abhaken
sleep 15
foto 03-abgehakt

curl -s http://127.0.0.1:8080/log > bilder/anfragen.json
python3 tools/pruefen.py bilder/anfragen.json

# 4. Dunkles Design
adb shell cmd uimode night yes
sleep 3
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/.TestHostActivity"
sleep 10
foto 04-widgets-dunkel
adb shell am start -W -n "$PKG/.MainActivity"
sleep 4
foto 05-einrichtung-dunkel
adb shell cmd uimode night no
