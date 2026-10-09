#!/usr/bin/env python3
"""Nachgebildetes Google-Skript für den Emulator-Test. Nur erfundene Daten.

Antwortet wie Apps Script: POST auf …/exec gibt eine Weiterleitung (302), die Antwort holt man dort mit GET.
GET /log zeigt alle Anfragen (ohne Schlüssel).
"""
import json
import sys
import threading
from datetime import datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse
from zoneinfo import ZoneInfo

KEY = "testschluessel-1234567890abcdef0"
PFAD = "/macros/s/test/exec"
LOCK = threading.Lock()
LOG = []
ANTWORTEN = {}

heute = datetime.now(ZoneInfo("Europe/Zurich")).date()


def d(n):
    return (heute + timedelta(days=n)).isoformat()


def aufgabe(id, title, area, due=None, next=False, src=None, done=False, removed=False):
    return {"id": id, "title": title, "area": area, "due": due, "done": done, "doneAt": d(-3) if done else None,
            "next": next, "removed": removed, "src": src or {"type": "manual"},
            "created": "2026-10-01T08:00:00.000Z", "remind": None}


TASKS = [
    aufgabe("t_steuern", "Steuererklärung abschicken", "privat", d(-2)),
    aufgabe("t_lager", "Lagerprogramm fertig planen", "pfadi", d(0)),
    aufgabe("t_bewerbung", "Bewerbung Sek I nachfassen", "bewerbung", d(2), next=True),
    aufgabe("t_roguelike", "Roguelike: Level-Editor testen", "projekte", next=True),
    aufgabe("mail_abc123", "Rechnung Muster AG CHF 42.00 zahlen", "privat", d(5),
            src={"type": "mail", "kind": "invoice", "ref": "abc123", "url": "https://mail.google.com/"}),
    aufgabe("t_velo", "Velo in den Service bringen", "inbox"),
    aufgabe("t_notiz", "Notiz an mich ansehen", "inbox", d(-1)),
    aufgabe("t_zivi", "Zivi-Mail vom 8.10. bearbeiten", "zivi"),
    aufgabe("t_ferien", "Ferien buchen", "privat", d(20)),
    aufgabe("t_alt", "Schon erledigt", "privat", d(-5), done=True),
    aufgabe("t_weg", "Entfernt", "privat", d(-5), removed=True),
]
BRIEFING = {"date": d(0), "probablyPaid": [{"id": "mail_abc123"}], "probablyDone": []}


def termin(cal, cal_name, id, title, sd, st=None, ed=None, et=None, ort=None):
    all_day = st is None
    return {"cal": cal, "calName": cal_name, "id": id, "serie": None, "title": title, "ort": ort,
            "allDay": all_day, "sd": sd, "st": st, "ed": ed or sd, "et": et, "edit": cal == "haupt", "popup": None}


EVENTS = [
    termin("haupt", "Hauptkalender", "e1", "Frühstück", d(0), "07:30", d(0), "08:30"),
    termin("haupt", "Hauptkalender", "e2", "Zahnarzt", d(1), "10:00", d(1), "10:45", ort="Luzern"),
    termin("familie", "Familie", "e3", "Herbstlager", d(2), None, d(4)),
    termin("haupt", "Hauptkalender", "e4", "Training", d(3), "18:30", d(3), "20:00"),
    termin("haupt", "Hauptkalender", "e5", "Spieleabend", d(6), "19:00", d(6), "23:00"),
    termin("haupt", "Hauptkalender", "e6", "Ausserhalb", d(9), "09:00", d(9), "10:00"),
]


def bearbeiten(req):
    if not isinstance(req, dict):
        return {"ok": False, "error": "bad_request"}
    if req.get("key") != KEY:
        return {"ok": False, "error": "unauthorized"}
    with LOCK:
        LOG.append({k: v for k, v in req.items() if k != "key"})
        a = req.get("action")
        if a == "load":
            return {"ok": True, "data": {"tasks": [t for t in TASKS if not t["removed"]], "briefing": BRIEFING,
                                         "last": None, "sheetUrl": None, "settings": {}}}
        if a == "termine":
            von, bis = req.get("von"), req.get("bis")
            evs = [e for e in EVENTS if e["ed"] >= von and e["sd"] <= bis]
            return {"ok": True, "events": evs, "errors": ["Kalender «Feiertage in der Schweiz»: kein Zugriff (Test)."]}
        if a == "update":
            for t in TASKS:
                if t["id"] == req.get("id"):
                    f = req.get("fields") or {}
                    if "done" in f:
                        t["done"] = bool(f["done"])
                        t["doneAt"] = (f.get("doneAt") or d(0)) if t["done"] else None
                    return {"ok": True, "task": t}
            return {"ok": False, "error": "Aufgabe nicht gefunden"}
        return {"ok": False, "error": "unknown_action"}


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if urlparse(self.path).path != PFAD:
            self.senden(404, {"error": "not found"})
            return
        n = int(self.headers.get("Content-Length") or 0)
        try:
            req = json.loads(self.rfile.read(n).decode("utf-8"))
        except ValueError:
            req = None
        antwort = bearbeiten(req)
        with LOCK:
            nr = str(len(ANTWORTEN) + 1)
            ANTWORTEN[nr] = antwort
        self.send_response(302)
        self.send_header("Location", "/echo?nr=" + nr)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        u = urlparse(self.path)
        if u.path == "/echo":
            nr = parse_qs(u.query).get("nr", [""])[0]
            with LOCK:
                antwort = ANTWORTEN.get(nr)
            self.senden(200 if antwort else 404, antwort or {"error": "unbekannt"})
        elif u.path == "/log":
            with LOCK:
                self.senden(200, LOG)
        else:
            self.senden(200, {"ok": True, "app": "Test"})

    def senden(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        print("mock: " + fmt % args, flush=True)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
    print("mock: heute ist", d(0), flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
