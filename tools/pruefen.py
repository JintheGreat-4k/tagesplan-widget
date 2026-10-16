#!/usr/bin/env python3
"""Prüft nach dem Emulator-Test, was die Widget-App an den nachgebildeten Lifehub geschickt hat (GET /test/log).

  python3 tools/pruefen.py anfragen.json                      einmal verbunden, geladen, Termine, abgehakt und wieder geöffnet
  python3 tools/pruefen.py --getrennt anfragen.json           zusätzlich: nach «Entfernen» genau ein Aufruf (401), dann Ruhe
  python3 tools/pruefen.py --getrennt --trennen anfragen.json zusätzlich: nach dem neuen Widget-Code verbunden, mit dem
                                                              alten Token neu verbunden, «Verbindung trennen»: genau ein
                                                              /api/widget/disconnect mit {} und dem Token, dann Ruhe

Der Mock protokolliert jede Anfrage, bevor er sie prüft. Ausser den drei Aufrufen des Tests selbst (GET /test/log,
POST /test/entfernen, POST /test/code) muss jede Anfrage ein POST auf einen Weg der Widget-App sein, ohne «?…», ohne
Token oder Widget-Code in der Adresse, ohne Token im Anfragetext.
"""
import json
import sys
from datetime import datetime, timedelta

WEGE = ("/api/widget/connect", "/api/widget/plan", "/api/widget/disconnect")
VERBINDEN, PLAN, TRENNEN = WEGE
TEST = {("GET", "/test/log"), ("POST", "/test/entfernen"), ("POST", "/test/code")}


def heute_in_zuerich():
    try:
        from zoneinfo import ZoneInfo
        return datetime.now(ZoneInfo("Europe/Zurich")).date()
    except Exception:  # Windows ohne Zeitzonen-Daten: Ortszeit des Rechners
        return datetime.now().date()


args = sys.argv[1:]
getrennt = "--getrennt" in args
trennen = "--trennen" in args
log = json.load(open([a for a in args if not a.startswith("--")][0], encoding="utf-8"))
heute = heute_in_zuerich()
fehler = []


def gibt(bed, text):
    if not bed:
        fehler.append(text)


def test(e):
    """Ein Aufruf des Tests selbst (curl), nicht der App"""
    return (e.get("methode"), e.get("weg")) in TEST


def marke(weg, ab=0):
    return next((i for i, e in enumerate(log) if i >= ab and e.get("methode") == "POST" and e.get("weg") == weg), len(log))


def app(teil):
    return [e for e in teil if not test(e)]


entfernt = marke("/test/entfernen")
neu_code = marke("/test/code", entfernt)
vorher = app(log[:entfernt])
nachher = app(log[entfernt + 1:neu_code])
neu = app(log[neu_code + 1:])

# Jede Anfrage der App: POST auf einen ihrer Wege, ohne «?…», JSON, nie Origin, Sec-Fetch-Site, ein Feld «key», ein
# Token oder Widget-Code in der Adresse oder ein Token im Anfragetext
for e in vorher + nachher + neu:
    k = e.get("kopf") or {}
    w = e.get("weg") or ""
    gibt(e.get("methode") == "POST", "nicht POST: %s %s" % (e.get("methode"), w))
    gibt("?" not in w, "Adresse mit «?…»: %s" % w)
    gibt(w in WEGE, "unbekannter Weg: %s" % w)
    gibt(e.get("geheimImWeg") is False, "Token oder Widget-Code in der Adresse: %s" % w)
    gibt(e.get("tokenImText") is False, "Token im Anfragetext: %s" % w)
    gibt(not k.get("origin"), "Origin mitgeschickt: %s" % w)
    gibt(not k.get("secFetch"), "Sec-Fetch-Site mitgeschickt: %s" % w)
    gibt(k.get("typ") == "application/json", "Content-Type ist nicht application/json: %r" % k.get("typ"))
    gibt(not e.get("key"), "Feld «key» mitgeschickt: %s" % w)


def verbinden_ok(c, wann):
    gibt(c.get("status") == 200, "%s: Verbinden mit Antwort %s statt 200" % (wann, c.get("status")))
    gibt(c.get("codeOk") is True, "%s: Verbinden nicht mit dem Test-Code" % wann)
    name = c.get("name") or ""
    gibt(name == "Widget-App" or name.startswith("Widget-App · "), "%s: Name der Verbindung: %r" % (wann, name))
    gibt(len(name) <= 40, "%s: Name länger als 40 Zeichen: %r" % (wann, name))


# Genau einmal verbunden, mit dem Test-Code und einem Namen; nie getrennt
verbunden = [i for i, e in enumerate(vorher) if e.get("weg") == VERBINDEN]
gibt(len(verbunden) == 1, "erwartet genau ein Verbinden, gefunden: %d" % len(verbunden))
if verbunden:
    verbinden_ok(vorher[verbunden[0]], "zuerst")
gibt(not [e for e in vorher if e.get("weg") == TRENNEN], "/api/widget/disconnect, ohne dass «Verbindung trennen» angetippt wurde")

# Jeder Aufruf von /api/widget/plan mit dem Token (Bearer), keiner vor dem Verbinden
plan = [e for e in vorher if e.get("weg") == PLAN]
for e in plan:
    gibt((e.get("kopf") or {}).get("auth") == "ok", "Aufruf ohne gültigen Bearer-Token: %s" % (e.get("anfrage") or {}).get("action"))
    gibt(e.get("status") == 200, "Antwort %s statt 200 auf %s" % (e.get("status"), e.get("anfrage")))
if verbunden and plan:
    gibt(vorher.index(plan[0]) > verbunden[0], "Aufruf vor dem Verbinden")

aktionen = [(e.get("anfrage") or {}).get("action") for e in plan]
print("Anfragen:", ["verbinden"] * len(verbunden) + aktionen)
gibt("load" in aktionen, "keine Anfrage «load»")
termine = [e["anfrage"] for e in plan if (e.get("anfrage") or {}).get("action") == "termine"]
gibt(bool(termine), "keine Anfrage «termine»")
if termine:
    gibt(termine[-1].get("von") == heute.isoformat(), "termine: von ist nicht heute")
    gibt(termine[-1].get("bis") == (heute + timedelta(days=6)).isoformat(), "termine: bis ist nicht heute + 6")

# Abhaken, dann wieder öffnen: zwei «update», danach jeweils neu geladen
updates = [i for i, a in enumerate(aktionen) if a == "update"]
gibt(len(updates) == 2, "erwartet zwei «update» (abhaken, wieder öffnen), gefunden: %d" % len(updates))
for n, (i, erledigt) in enumerate(zip(updates, (True, False))):
    u = plan[i]["anfrage"]
    f = u.get("fields") or {}
    gibt(u.get("id") == "t_steuern", "update %d: nicht die erste Aufgabe im Fokus: %s" % (n + 1, u.get("id")))
    gibt(f.get("done") is erledigt, "update %d: fields.done ist nicht %s" % (n + 1, erledigt))
    if erledigt:
        gibt(f.get("doneAt") == heute.isoformat(), "update 1: fields.doneAt ist nicht heute")
    else:
        gibt("doneAt" in f and f["doneAt"] is None, "update 2: fields.doneAt ist nicht null")
    gibt("load" in aktionen[i + 1:], "nach update %d wurde nicht neu geladen" % (n + 1))

if getrennt:
    gibt(entfernt < len(log), "Marke /test/entfernen fehlt im Protokoll")
    gibt(len(nachher) == 1, "nach dem Entfernen erwartet genau einen Aufruf, dann Ruhe; gefunden: %d" % len(nachher))
    if nachher:
        gibt(nachher[0].get("weg") == PLAN and nachher[0].get("status") == 401,
             "nach dem Entfernen: %s mit %s statt /api/widget/plan mit 401" % (nachher[0].get("weg"), nachher[0].get("status")))

if trennen:
    gibt(neu_code < len(log), "Marke /test/code (neuer Widget-Code nach dem Entfernen) fehlt im Protokoll")
    print("Danach:", [e.get("weg", "").rsplit("/", 1)[-1] + ("" if e.get("weg") != PLAN else " " + str((e.get("anfrage") or {}).get("action")))
                      for e in neu])
    v = [i for i, e in enumerate(neu) if e.get("weg") == VERBINDEN]
    gibt(len(v) == 2, "nach dem neuen Widget-Code erwartet zwei Verbinden (neu, dann nochmals mit dem alten Token), gefunden: %d" % len(v))
    if v:
        c = neu[v[0]]
        verbinden_ok(c, "nach «Getrennt»")
        gibt((c.get("kopf") or {}).get("auth") == "fehlt", "nach «Getrennt»: Verbinden schickt noch einen alten Token mit")
    if len(v) > 1:
        c = neu[v[1]]
        verbinden_ok(c, "nochmals")
        gibt((c.get("kopf") or {}).get("auth") == "ok" and c.get("ersetzt") is True,
             "nochmals verbinden: alter Token nicht als Bearer mitgeschickt (der Lifehub hätte zwei Zeilen)")
        gibt(any((e.get("anfrage") or {}).get("action") == "load" for e in neu[v[1] + 1:] if e.get("weg") == PLAN),
             "nach dem zweiten Verbinden nicht geladen")
    for e in neu:
        if e.get("weg") == PLAN:
            gibt((e.get("kopf") or {}).get("auth") == "ok" and e.get("status") == 200,
                 "nach dem neuen Widget-Code: %s mit Bearer %s, Antwort %s" % ((e.get("anfrage") or {}).get("action"),
                                                                             (e.get("kopf") or {}).get("auth"), e.get("status")))
    t = [i for i, e in enumerate(neu) if e.get("weg") == TRENNEN]
    gibt(len(t) == 1, "«Verbindung trennen»: erwartet genau ein /api/widget/disconnect, gefunden: %d" % len(t))
    if t:
        d = neu[t[0]]
        gibt((d.get("kopf") or {}).get("auth") == "ok", "Trennen ohne den gültigen Token (Bearer %s)" % (d.get("kopf") or {}).get("auth"))
        gibt(d.get("status") == 200, "Trennen: Antwort %s statt 200" % d.get("status"))
        gibt(d.get("anfrage") == {}, "Trennen: Anfragetext ist nicht {}: %r" % d.get("anfrage"))
        gibt(t[0] == len(neu) - 1, "nach dem Trennen noch Anfragen: %s" % [e.get("weg") for e in neu[t[0] + 1:]])
        gibt(not v or t[0] > v[-1], "Trennen vor dem letzten Verbinden")

if fehler:
    print("FEHLER:\n- " + "\n- ".join(fehler))
    sys.exit(1)
print("Alles in Ordnung.")
