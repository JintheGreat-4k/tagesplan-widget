#!/usr/bin/env python3
"""Nachgebildeter Lifehub für den Emulator-Test der Widget-App. Nur erfundene Daten.

Antwortet wie der Lifehub (server/widget.js) auf die Wege der Widget-App, mit denselben Prüfungen:
  POST /api/widget/connect     {code, name?}  Test-Code TEST-2345 (danach der aus /test/code), genau einmal
                                              → 200 {ok, token}; sonst 403 wrong_code. Mit «Authorization: Bearer
                                              <alter Token>» ersetzt die neue Verbindung die alte (im Protokoll «ersetzt»)
  POST /api/widget/plan        {action, …}    nur mit «Authorization: Bearer <Token>», sonst 401 unauthenticated;
                                              load, termine (höchstens 7 Tage), update (nur done und doneAt), sonst 400;
                                              in der Antwort nur die Felder, die der Lifehub der Widget-App weitergibt
  POST /api/widget/disconnect  {}             nur mit Token → 200 {ok}; der Token gilt danach nicht mehr
Jede Anfrage mit Origin oder Sec-Fetch-Site: 403 forbidden_origin (kein Browser ruft diese Wege auf). Nur JSON (415),
höchstens 4 KiB (413). Antwortet direkt, ohne Weiterleitung. Andere Wege 404, andere Methoden 405.
Jede Anfrage kommt zuerst ins Protokoll, vor jeder Prüfung: Methode, ganze Adresse samt «?…» (Token und Widget-Codes
darin unkenntlich), Kopfzeilen, und ob ein Token oder Widget-Code in der Adresse oder ein Token im Anfragetext steht.
Nur für den Test (tools/emulator_test.sh; tools/pruefen.py lässt genau diese drei Aufrufe aus):
  GET  /test/log        alle Anfragen, ohne Token und ohne Code
  POST /test/entfernen  vergisst alle Tokens, wie «Entfernen» auf der Startseite des Lifehub
  POST /test/code       {code}: ein neuer Widget-Code gilt (einmal), der alte nicht mehr, wie «Widget-App verbinden»

Aufruf: python3 tools/mock_server.py [Port]   (hört nur auf 127.0.0.1; der Emulator erreicht ihn als 10.0.2.2)
"""
import json
import re
import secrets
import sys
import threading
from datetime import date, datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote

TEST_CODE = "TEST2345"  # gilt beim Start einmal (TEST-2345)
CODE_FORM = re.compile(r"^[A-HJ-NP-Z2-9]{8}$")
KLEIN = 4096
WEGE = ("/api/widget/connect", "/api/widget/plan", "/api/widget/disconnect")
JSON_TYP = re.compile(r'^application/json(\s*;\s*charset="?utf-8"?)?$', re.I)
BEARER = re.compile(r"^Bearer ([A-Za-z0-9_-]{43})$")
DATUM = re.compile(r"^\d{4}-\d{2}-\d{2}$")
AUFGABE_FELDER = ("id", "title", "area", "due", "created", "done", "doneAt", "next", "removed")
TERMIN_FELDER = ("cal", "calName", "id", "serie", "title", "ort", "allDay", "sd", "st", "ed", "et")
LOCK = threading.Lock()
LOG = []
TOKENS = {}         # gültige Tokens → Name der Verbindung
ALLE_TOKENS = set() # jeder je ausgegebene Token, auch entfernte: nie in einer Adresse oder einem Anfragetext
CODE = {"frei": TEST_CODE}  # der eine Widget-Code, der gerade gilt (normalisiert), None = verbraucht
ALLE_CODES = {TEST_CODE}    # jeder je ausgegebene Widget-Code: nie in einer Adresse


def heute_in_zuerich():
    try:
        from zoneinfo import ZoneInfo
        return datetime.now(ZoneInfo("Europe/Zurich")).date()
    except Exception:  # Windows ohne Zeitzonen-Daten: Ortszeit des Rechners
        return datetime.now().date()


heute = heute_in_zuerich()


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
BRIEFING = {"date": d(0), "probablyPaid": [{"id": "mail_abc123", "title": "Rechnung", "url": "https://mail.google.com/"}],
            "probablyDone": [], "events": [], "starCanGo": []}


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


def normal(code):
    """Wie normalise im Lifehub (server/second.js)"""
    return re.sub(r"[IO]", "", re.sub(r"[^A-Za-z2-9]", "", code).upper())


def geheimnisse():
    """Was nie in einer Adresse stehen darf und nie ins Protokoll kommt: jeder Token, jeder Widget-Code (beide Formen)"""
    with LOCK:
        g = set(ALLE_TOKENS)
        for c in ALLE_CODES:
            g |= {c, c[:4] + "-" + c[4:]}
    return g


def maskieren(text, geheim):
    for g in sorted(geheim, key=len, reverse=True):
        text = re.sub(re.escape(g), "<geheim>", text, flags=re.I)
    return text


def aufgabe_weiter(t):
    """Nur was der Lifehub der Widget-App von einer Aufgabe weitergibt"""
    p = {k: t[k] for k in AUFGABE_FELDER}
    p["src"] = {k: v for k, v in t["src"].items() if k in ("type", "kind")}
    return p


def datum(s):
    if not isinstance(s, str) or not DATUM.match(s):
        return None
    try:
        return date.fromisoformat(s)
    except ValueError:
        return None


def plan_gueltig(req):
    """Genau die Formen, die der Lifehub für /api/widget/plan annimmt (alles andere: 400 vor jedem Aufruf des Skripts)"""
    a, felder = req.get("action"), set(req)
    if a == "load":
        return felder == {"action"}
    if a == "termine":
        von, bis = datum(req.get("von")), datum(req.get("bis"))
        return felder == {"action", "von", "bis"} and von is not None and bis is not None and 0 <= (bis - von).days <= 6
    if a == "update":
        i, f = req.get("id"), req.get("fields")
        if felder != {"action", "id", "fields"} or not isinstance(i, str) or not 1 <= len(i) <= 200:
            return False
        if not isinstance(f, dict) or not f or not set(f) <= {"done", "doneAt"}:
            return False
        if "done" in f and not isinstance(f["done"], bool):
            return False
        return "doneAt" not in f or f["doneAt"] is None or datum(f["doneAt"]) is not None
    return False


def plan(req):
    a = req["action"]
    if a == "load":
        b = {"date": BRIEFING["date"], "probablyDone": [{"id": x["id"]} for x in BRIEFING["probablyDone"]],
             "probablyPaid": [{"id": x["id"]} for x in BRIEFING["probablyPaid"]]}
        return {"ok": True, "data": {"tasks": [aufgabe_weiter(t) for t in TASKS if not t["removed"]], "briefing": b}}
    if a == "termine":
        evs = [{k: e[k] for k in TERMIN_FELDER} for e in EVENTS if e["ed"] >= req["von"] and e["sd"] <= req["bis"]]
        return {"ok": True, "events": evs, "errors": ["Kalender «Feiertage in der Schweiz»: kein Zugriff (Test)."]}
    for t in TASKS:
        if t["id"] == req["id"]:
            f = req["fields"]
            if "done" in f:
                t["done"] = f["done"]
                t["doneAt"] = (f.get("doneAt") or d(0)) if t["done"] else None
            return {"ok": True, "task": aufgabe_weiter(t)}
    return {"ok": False, "error": "Aufgabe nicht gefunden"}


class Handler(BaseHTTPRequestHandler):
    def aufnehmen(self):
        """Jede Anfrage zuerst ins Protokoll (Methode, ganze Adresse samt «?…»), erst dann weiterleiten und prüfen. So
        fallen auch ein falscher Weg, eine andere Methode oder ein Token in der Adresse oder im Anfragetext auf."""
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            n = 0
        roh = self.rfile.read(n) if 0 < n <= KLEIN else b""
        geheim = geheimnisse()
        adresse = (self.path + " " + unquote(self.path)).lower()
        text = roh.decode("utf-8", "replace")
        m = BEARER.match(self.headers.get("Authorization") or "")
        token = m.group(1) if m else None
        with LOCK:
            auth = "fehlt" if "Authorization" not in self.headers else "ok" if token in TOKENS else "falsch"
            token_im_text = any(t in text for t in ALLE_TOKENS)
        e = {"methode": self.command, "weg": maskieren(self.path, geheim),
             "kopf": {"origin": "Origin" in self.headers, "secFetch": "Sec-Fetch-Site" in self.headers,
                      "typ": self.headers.get("Content-Type"), "auth": auth},
             "geheimImWeg": any(g.lower() in adresse for g in geheim), "tokenImText": token_im_text}
        with LOCK:
            LOG.append(e)
        return e, roh, n, token

    def do_POST(self):
        e, roh, n, token = self.aufnehmen()
        weg = self.path.split("?")[0]
        try:
            req = json.loads(roh.decode("utf-8")) if roh else None
        except ValueError:
            req = None
        if weg == "/test/entfernen":
            with LOCK:
                TOKENS.clear()
            print("mock: Test: alle Tokens vergessen (wie «Entfernen» im Lifehub)", flush=True)
            return self.fertig(e, 200, {"ok": True})
        if weg == "/test/code":
            c = normal(req["code"]) if isinstance(req, dict) and isinstance(req.get("code"), str) else ""
            if not CODE_FORM.match(c):
                return self.fertig(e, 400, {"error": "bad_request"})
            with LOCK:
                CODE["frei"] = c
                ALLE_CODES.add(c)
            print("mock: Test: neuer Widget-Code gilt (wie «Widget-App verbinden» im Tagesplan)", flush=True)
            return self.fertig(e, 200, {"ok": True})
        if weg not in WEGE:
            return self.fertig(e, 404, {"error": "not_found"})
        if isinstance(req, dict):
            # Beim Verbinden nie den Code ins Protokoll, sonst ohne «key» und ohne Token oder Code im Text
            anfrage = None
            if weg != "/api/widget/connect":
                anfrage = json.loads(maskieren(json.dumps({k: v for k, v in req.items() if k != "key"}), geheimnisse()))
            with LOCK:
                e["key"] = "key" in req
                if anfrage is not None:
                    e["anfrage"] = anfrage
        status, antwort = self.bearbeiten(weg, e, token, n, req)
        return self.fertig(e, status, antwort)

    def bearbeiten(self, weg, e, token, n, req):
        if e["kopf"]["origin"] or e["kopf"]["secFetch"]:
            return 403, {"error": "forbidden_origin"}
        if weg != "/api/widget/connect" and e["kopf"]["auth"] != "ok":
            return 401, {"error": "unauthenticated"}
        if not JSON_TYP.match((self.headers.get("Content-Type") or "").strip()):
            return 415, {"error": "unsupported_media_type"}
        if n > KLEIN:
            return 413, {"error": "too_large"}
        if not isinstance(req, dict):
            return 400, {"error": "bad_request"}
        if weg == "/api/widget/connect":
            name = req.get("name", "")
            if not isinstance(req.get("code"), str) or set(req) - {"code", "name"} or not isinstance(name, str):
                return 400, {"error": "bad_request"}
            with LOCK:
                e["name"] = name
                e["codeOk"] = CODE["frei"] is not None and normal(req["code"]) == CODE["frei"]
                if not e["codeOk"]:
                    return 403, {"error": "wrong_code"}
                CODE["frei"] = None
                e["ersetzt"] = TOKENS.pop(token, None) is not None if token else False
                neu = secrets.token_urlsafe(32)
                TOKENS[neu] = name.strip()[:40] or "Widget-App"
                ALLE_TOKENS.add(neu)
            return 200, {"ok": True, "token": neu}
        if weg == "/api/widget/disconnect":
            if req:
                return 400, {"error": "bad_request"}
            with LOCK:
                TOKENS.pop(token, None)
            return 200, {"ok": True}
        if not plan_gueltig(req):
            return 400, {"error": "bad_request"}
        with LOCK:
            return 200, plan(req)

    def do_GET(self):
        e = self.aufnehmen()[0]
        if self.path.split("?")[0] != "/test/log":
            return self.fertig(e, 404, {"error": "not_found"})
        with LOCK:
            e["status"] = 200
            body = json.dumps(LOG, ensure_ascii=False).encode("utf-8")
        self.senden_roh(200, body)

    def anders(self):
        """PUT, DELETE, PATCH, OPTIONS, HEAD: Die Widget-App schickt nur POST"""
        self.fertig(self.aufnehmen()[0], 405, {"error": "method_not_allowed"})

    do_PUT = do_DELETE = do_PATCH = do_OPTIONS = do_HEAD = anders

    def fertig(self, e, status, antwort):
        with LOCK:
            e["status"] = status
        was = (e.get("anfrage") or {}).get("action") or ""
        print("mock: %s %s %s: %d · Bearer %s · %s · %s" % (e["methode"], e["weg"], was, status, e["kopf"]["auth"],
              "mit Origin" if e["kopf"]["origin"] else "ohne Origin", "mit key" if e.get("key") else "ohne key"), flush=True)
        self.senden_roh(status, json.dumps(antwort, ensure_ascii=False).encode("utf-8"))

    def senden_roh(self, code, body):
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def log_message(self, fmt, *args):
        # Auch die Zugriffszeile nie mit einem Token oder Widget-Code aus der Adresse
        print("mock: " + maskieren(fmt % args, geheimnisse()), flush=True)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
    print("mock: heute ist", d(0), flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
