#!/usr/bin/env python3
"""Prüft nach dem Emulator-Test, was die Widget-App an das nachgebildete Skript geschickt hat."""
import json
import sys
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

log = json.load(open(sys.argv[1], encoding="utf-8"))
heute = datetime.now(ZoneInfo("Europe/Zurich")).date()
fehler = []


def gibt(bed, text):
    if not bed:
        fehler.append(text)


aktionen = [e.get("action") for e in log]
print("Anfragen:", aktionen)
gibt("load" in aktionen, "keine Anfrage «load»")
termine = [e for e in log if e.get("action") == "termine"]
gibt(bool(termine), "keine Anfrage «termine»")
if termine:
    gibt(termine[-1].get("von") == heute.isoformat(), "termine: von ist nicht heute")
    gibt(termine[-1].get("bis") == (heute + timedelta(days=6)).isoformat(), "termine: bis ist nicht heute + 6")
updates = [e for e in log if e.get("action") == "update"]
gibt(len(updates) == 1, "erwartet genau ein «update», gefunden: %d" % len(updates))
if updates:
    u = updates[0]
    gibt(u.get("id") == "t_steuern", "abgehakt wurde nicht die erste Aufgabe im Fokus: %s" % u.get("id"))
    f = u.get("fields") or {}
    gibt(f.get("done") is True, "fields.done ist nicht true")
    gibt(f.get("doneAt") == heute.isoformat(), "fields.doneAt ist nicht heute")
    i = aktionen.index("update")
    gibt("load" in aktionen[i + 1:], "nach dem Abhaken wurde nicht neu geladen")

if fehler:
    print("FEHLER:\n- " + "\n- ".join(fehler))
    sys.exit(1)
print("Alles in Ordnung.")
