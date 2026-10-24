"""HTTP-Schnittstelle für App und Web-Oberfläche."""
import hmac
import json
import os
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler

from . import basis, ereignisse, dienste, claude, gesundheit, stimmen, wissen, home, bots, sitzungen, gedaechtnis, rechner, automatik, team, vorschlaege, mcp


# ── HTTP ───────────────────────────────────────────────────────────

class Handler(BaseHTTPRequestHandler):
    server_version = "nookbot/" + basis.VERSION
    sys_version = ""
    protocol_version = "HTTP/1.1"

    def _send(self, code: int, obj=None):
        data = b"" if obj is None else json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def _body(self) -> dict:
        n = int(self.headers.get("Content-Length") or 0)
        if not 0 < n < 25_000_000:  # PDFs als Base64
            return {}
        try:
            return json.loads(self.rfile.read(n).decode() or "{}")
        except ValueError:
            return {}

    def _auth(self, token: str) -> bool:
        h = self.headers.get("Authorization", "")
        good = bool(token) and h.startswith("Bearer ") and hmac.compare_digest(h[7:].strip().encode(), token.encode())
        if not good:
            time.sleep(0.3)
            self._send(401, {"error": "unauthorized"})
        return good

    def log_message(self, fmt, *args):
        pass

    def do_GET(self):  # noqa: N802
        u = urllib.parse.urlparse(self.path)
        if u.path == "/ping":
            return self._send(200, {"ok": True, "dienst": "nookbot", "version": basis.VERSION})
        if u.path in ("/", "/index.html", "/manifest.webmanifest", "/icon.svg"):
            return self._static("index.html" if u.path in ("/", "/index.html") else u.path.lstrip("/"))
        if not self._auth(basis.pico_token()):
            return
        q = {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}
        try:
            return self._get(u.path, q)
        except KeyError as e:
            return self._send(404, {"error": str(e).strip("'")})
        except Exception as e:  # noqa: BLE001
            return self._send(400, {"error": str(e)})

    def _get(self, p: str, q: dict):
        if p == "/api/state":
            return self._send(200, {"version": basis.VERSION, "seq": ereignisse.EV.seq, "bots": [bots.bot_public(b) for b in bots.bots()],
                                    "sessions": [dict(x, laeuft=sitzungen.SESS.running(x["id"])) for x in sitzungen.SESS.index() if x.get("art") != "intern"],
                                    "routinen": automatik.routinen(), "ausloeser": automatik.ausloeser(), "erinnerungen": automatik.erinnerungen(), "ablaeufe": gedaechtnis.ablaeufe(),
                                    "gedaechtnis": gedaechtnis.gedaechtnis(), "freigaben_web": bool(basis.conf().get("freigabe_key")),
                                    "ntfy": bool((basis.conf().get("ntfy") or {}).get("url"))})
        if p == "/api/erinnerungen":
            return self._send(200, {"erinnerungen": automatik.erinnerungen()})
        if p == "/api/ausloeser":
            return self._send(200, {"ausloeser": automatik.ausloeser(), "quellen": automatik.AUSLOESER_QUELLEN})
        if p == "/api/anrufe/warten":
            return self._send(200, automatik.anrufe_warten(int(q.get("seit") or 0), int(q.get("sekunden") or 50)))
        if p == "/api/regeln":
            return self._send(200, {"werkzeuge": rechner.REGEL_WERKZEUGE, "nie_frei": sorted(rechner.REGEL_NIE_FREI),
                                    "frei_max": int(basis.conf().get("regeln_frei_max") or rechner.REGEL_FREI_MAX)})
        if p == "/api/arbeit":
            return self._send(200, {"dateien": rechner.arbeit_liste()})
        m = re.match(r"^/api/arbeit/(.+)$", p)
        if m:
            fp = os.path.join(rechner.arbeit_dir(), rechner.arbeit_name(urllib.parse.unquote(m.group(1))))
            if not os.path.isfile(fp):
                raise KeyError("Datei gibt es nicht.")
            import mimetypes
            data = open(fp, "rb").read()
            ct = mimetypes.guess_type(fp)[0] or "application/octet-stream"
            self.send_response(200)
            # nie als Seite im Browser ausführen lassen – Bilder dürfen angezeigt werden, alles andere wird geladen
            self.send_header("Content-Type", ct if ct.startswith("image/") and ct != "image/svg+xml" else "application/octet-stream")
            self.send_header("Content-Disposition", ("inline" if ct.startswith("image/") and ct != "image/svg+xml" else "attachment")
                             + "; filename*=UTF-8''" + urllib.parse.quote(os.path.basename(fp)))
            self.send_header("Content-Length", str(len(data)))
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)
            return None
        if p == "/api/aktivitaet":
            return self._send(200, {"eintraege": automatik.aktivitaet_liste(int(q.get("tage") or 7), str(q.get("bot") or ""), str(q.get("art") or ""),
                                                                 bool(q.get("hintergrund")))})
        if p == "/api/gedaechtnis":
            return self._send(200, {"eintraege": gedaechtnis.gedaechtnis()})
        if p == "/api/ablaeufe":
            return self._send(200, {"ablaeufe": gedaechtnis.ablaeufe()})
        if p == "/api/vorschlaege":
            return self._send(200, {"vorschlaege": vorschlaege.liste()[::-1], "einstellungen": vorschlaege.einstellungen()})
        if p == "/api/freigaben":
            return self._send(200, home.freigaben_proxy("GET", "/app/freigaben" + ("?offen=1" if q.get("offen") else "")))
        if p == "/api/events":
            return self._sse(int(q.get("since") or 0))
        m = re.match(r"^/api/sessions/([a-z0-9-]+)$", p)
        if m:
            s = sitzungen.SESS.get(m.group(1))
            if not s:
                raise KeyError("Unterhaltung gibt es nicht.")
            if s.get("ungelesen"):
                s["ungelesen"] = 0
                sitzungen.SESS.save(s["id"])
            return self._send(200, dict(s, laeuft=sitzungen.SESS.running(s["id"])))
        if p == "/api/suche":
            q_ = str(q.get("q") or "").strip().lower()
            if len(q_) < 2:
                return self._send(200, {"treffer": []})
            hits = []
            for meta in sitzungen.SESS.index():
                if meta.get("art") == "intern":
                    continue
                s = sitzungen.SESS.get(meta["id"])
                for m_ in reversed((s or {}).get("msgs", [])):
                    t = m_.get("text") or ""
                    i = t.lower().find(q_)
                    if m_.get("role") in ("user", "bot") and i >= 0:
                        a, b_ = max(0, i - 60), min(len(t), i + len(q_) + 90)
                        hits.append({"s": meta["id"], "titel": meta.get("titel"), "bot": meta.get("bot"), "art": meta.get("art"),
                                     "ts": m_.get("ts", 0), "auszug": ("…" if a else "") + t[a:b_].replace("\n", " ") + ("…" if b_ < len(t) else "")})
                        break
                if len(hits) >= 40:
                    break
            return self._send(200, {"treffer": hits})
        if p == "/api/notify":
            d = basis.read_json(basis.path("notify.json"), {"last": 0, "items": []})
            n = int(q.get("since") or 0)
            return self._send(200, {"last": d["last"], "items": [x for x in d["items"] if x["id"] > n]})
        if p == "/api/rechte":
            k = home.katalog(neu=q.get("neu") == "1")
            return self._send(200, {"konten": k["konten"], "db": k["db"], "gitlab": k["gitlab"], "statistik": k["statistik"],
                                    "slack": k["slack"], "kanaele": k["kanaele"]})
        if p == "/api/elevenlabs":
            return self._send(200, stimmen.el_kontingent() or {})
        if p == "/api/stimmen":
            return self._send(200, {"stimmen": stimmen.stimmen_liste(), "standard": stimmen.STANDARD_STIMME, "aussprache": stimmen.aussprache(),
                                    "elevenlabs": stimmen.el_kontingent() if q.get("kontingent") else bool(stimmen.el_conf().get("api_key"))})
        if p == "/api/gesundheit/info":
            return self._send(200, gesundheit.gesundheit_info())
        if p == "/api/claude":
            return self._send(200, claude.claude_kosten())
        if p == "/api/modelle":
            try:
                oll = dienste.modelle()
            except Exception as e:  # noqa: BLE001 – Claude soll auch ohne Ollama wählbar sein
                basis.log("Ollama-Modelle:", e)
                oll = []
            return self._send(200, {"modelle": oll, "claude": claude.CLAUDE_MODELLE if claude.claude_conf().get("api_key") else []})
        if p == "/api/guthaben":
            return self._send(200, dienste.ollama_get("https://ollama.com/api/balance"))
        if p == "/api/datenbanken":
            return self._send(200, home.db_lib())
        if p == "/api/schema":
            args = {"datenbank": q.get("db", "")}
            if q.get("tabelle"):
                args["tabelle"] = q["tabelle"]
            return self._send(200, {"text": home.mcp_call("db_schema", args)})
        if p == "/api/zugriffe":
            tools = json.loads(home.mcp_call("", method="tools/list")).get("tools", [])
            return self._send(200, {"konten": home.mcp_call("konten"), "werkzeuge": [t["name"] for t in tools],
                                    "bots": [{"id": b["id"], "name": b["name"], "zugriffe": b.get("zugriffe", False)} for b in bots.bots()]})
        if p == "/api/wissen":
            return self._send(200, {"global": wissen.wissen_list("global"), "bots": {b["id"]: wissen.wissen_list(b["id"]) for b in bots.bots()}})
        m = re.match(r"^/api/wissen/([a-z0-9-]+)/(.+)$", p)
        if m:
            fp = os.path.join(wissen.wissen_dir(m.group(1)), os.path.basename(urllib.parse.unquote(m.group(2))))
            with open(fp, encoding="utf-8", errors="replace") as f:
                return self._send(200, {"name": os.path.basename(fp), "text": f.read()})
        raise KeyError("Gibt es nicht.")

    def _static(self, name: str):
        fp = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "web", name)  # web/ liegt neben nookbot_server.py
        try:
            data = open(fp, "rb").read()
        except OSError:
            return self._send(404, {"error": "Web-Oberfläche nicht installiert"})
        ct = {"html": "text/html; charset=utf-8", "webmanifest": "application/manifest+json", "svg": "image/svg+xml"}[name.rsplit(".", 1)[-1]]
        self.send_response(200)
        self.send_header("Content-Type", ct)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-cache")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
                                                    "img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
        self.end_headers()
        self.wfile.write(data)

    def _sse(self, since: int):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Accel-Buffering", "no")
        self.end_headers()
        ereignisse.EV.clients += 1
        try:
            cur = since
            while True:
                evs = ereignisse.EV.since(cur)
                if evs is None:
                    self.wfile.write(f"data: {json.dumps({'t': 'reset', 'seq': ereignisse.EV.seq})}\n\n".encode())
                    cur = ereignisse.EV.seq
                elif evs:
                    for s, e in evs:
                        self.wfile.write(f"id: {s}\ndata: {json.dumps(dict(e, seq=s), ensure_ascii=False)}\n\n".encode())
                    cur = evs[-1][0]
                else:
                    with ereignisse.EV.cond:
                        ereignisse.EV.cond.wait(45)  # Lebenszeichen alle 45 s – sparsam für den Akku im Hintergrund
                    if ereignisse.EV.seq == cur:
                        self.wfile.write(b": ping\n\n")
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        finally:
            ereignisse.EV.clients -= 1

    def _claude_proxy(self, p: str):
        """Claude-Anfragen der Bots durchreichen und den Verbrauch aus usage mitschreiben (nur mit dem eigenen Schlüssel)."""
        key = claude.claude_conf().get("api_key") or ""
        got = self.headers.get("x-api-key") or self.headers.get("Authorization", "").removeprefix("Bearer ").strip()
        if not key or not hmac.compare_digest(got.encode(), key.encode()):
            time.sleep(0.3)
            return self._send(401, {"type": "error", "error": {"type": "authentication_error", "message": "unbekannter Schlüssel"}})
        n_ = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n_) if 0 < n_ < 40_000_000 else b""
        ziel = "/v1/" + p.split("/v1/", 1)[1] if "/v1/" in p else "/v1/messages"
        openai = ziel.rstrip("/").endswith("/chat/completions")
        body = claude.claude_anpassen(body, openai)
        q = self.path.split("?", 1)[1] if "?" in self.path else ""
        hdr = {k: v for k, v in self.headers.items() if k.lower() in ("x-api-key", "authorization", "anthropic-version", "anthropic-beta",
                                                                       "content-type", "accept", "user-agent")}
        req = urllib.request.Request(claude.CLAUDE_API + ziel + (("?" + q) if q else ""), data=body, method="POST", headers=hdr)
        try:
            model = json.loads(body or b"{}").get("model", "")
        except ValueError:
            model = ""
        try:
            r = urllib.request.urlopen(req, timeout=900)
        except urllib.error.HTTPError as e:
            data = e.read()
            self.send_response(e.code)
            for k in ("Content-Type", "retry-after", "request-id"):
                if e.headers.get(k):
                    self.send_header(k, e.headers[k])
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        except urllib.error.URLError as e:
            return self._send(502, {"type": "error", "error": {"type": "api_error", "message": f"Anthropic nicht erreichbar: {e.reason}"}})
        zaehlen = openai or ziel.rstrip("/").endswith("/messages")  # count_tokens & Co. kosten nichts
        with r:
            ct = r.headers.get("Content-Type", "")
            self.send_response(r.status)
            for k, v in r.headers.items():
                if k.lower() in ("content-type", "request-id", "anthropic-ratelimit-requests-remaining", "anthropic-ratelimit-tokens-remaining"):
                    self.send_header(k, v)
            if "event-stream" not in ct:
                data = r.read()
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
                if zaehlen:
                    try:
                        o = json.loads(data)
                        u = o.get("usage") or {}
                        claude.claude_buchen(o.get("model") or model, claude.usage_von_openai(u) if openai else u)
                    except (ValueError, AttributeError):
                        pass
                return
            # Streaming: Ereignisse sofort weiterreichen; usage steckt in message_start (Eingabe) und message_delta (Ausgabe, kumuliert)
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            self.end_headers()
            self.close_connection = True
            usage, m_ = {}, model
            for line in r:
                self.wfile.write(line)
                if line.startswith(b"\n") or line == b"\r\n":
                    self.wfile.flush()
                if zaehlen and line.startswith(b"data: ") and b"usage" in line:
                    try:
                        ev = json.loads(line[6:])
                    except ValueError:
                        continue
                    if openai:
                        if ev.get("usage"):
                            usage = claude.usage_von_openai(ev["usage"])
                            m_ = ev.get("model") or m_
                    elif ev.get("type") == "message_start":
                        m_ = ev.get("message", {}).get("model") or m_
                        usage.update({k: v for k, v in (ev.get("message", {}).get("usage") or {}).items() if v is not None})
                    elif ev.get("type") == "message_delta":
                        usage.update({k: v for k, v in (ev.get("usage") or {}).items() if v is not None})
            self.wfile.flush()
            if zaehlen and usage:
                claude.claude_buchen(m_, usage)

    def do_POST(self):  # noqa: N802
        p = self.path.split("?")[0]
        if p.startswith("/anthropic/"):
            # Nur die Bots auf dem Pi: tailscale serve reicht Tailnet-Anfragen ebenfalls über 127.0.0.1 weiter, setzt aber X-Forwarded-For
            if self.client_address[0] != "127.0.0.1" or self.headers.get("X-Forwarded-For") or self.headers.get("Tailscale-User-Login"):
                return self._send(403, {"error": "nur lokal"})
            return self._claude_proxy(p)
        m = re.match(r"^/homemcp/([a-z0-9-]+)$", p)
        if m:
            if not self._auth(basis.conf()["mcp_token"]):
                return
            caller = bots.bot(m.group(1))
            if not caller:
                return self._send(404, {"error": "Bot gibt es nicht"})
            try:
                r = home.homemcp_rpc(self._body(), caller)
            except Exception as e:  # noqa: BLE001
                return self._send(502, {"error": str(e)})
            return self._send(202) if r is None else self._send(200, r)
        m = re.match(r"^/mcp/([a-z0-9-]+)$", p)
        if m:
            if not self._auth(basis.conf()["mcp_token"]):
                return
            caller = bots.bot(m.group(1))
            if not caller:
                return self._send(404, {"error": "Bot gibt es nicht"})
            msg = self._body()
            r = mcp.mcp_rpc(msg, caller)
            return self._send(202) if r is None else self._send(200, r)
        if not self._auth(basis.pico_token()):
            return
        d = self._body()
        try:
            return self._post(p, d)
        except KeyError as e:
            return self._send(404, {"error": str(e).strip("'")})
        except Exception as e:  # noqa: BLE001
            return self._send(400, {"error": str(e)})

    def _post(self, p: str, d: dict):
        if p == "/api/sessions":
            b = bots.bot(str(d.get("bot") or bots.kapitaen()["id"]))
            if not b:
                raise KeyError("Bot gibt es nicht.")
            return self._send(200, sitzungen.SESS.meta(sitzungen.SESS.create(b["id"])))
        m = re.match(r"^/api/sessions/([a-z0-9-]+)/senden$", p)
        if m:
            sitzungen.send(m.group(1), str(d.get("text") or ""))
            return self._send(200, {"ok": True})
        if p == "/api/runden":
            return self._send(200, team.runde(str(d.get("frage") or "").strip(), list(d.get("bots") or []), d.get("kapitaen"), int(d.get("gespraech") or 0)))
        m = re.match(r"^/api/ausloeser/([a-z0-9]+)/pruefen$", p)
        if m:
            a = next((x for x in automatik.ausloeser() if x["id"] == m.group(1)), None)
            if not a:
                raise KeyError("Auslöser gibt es nicht.")
            return self._send(200, {"ergebnis": automatik.ausloeser_pruefen(a, sofort=True), "ausloeser": automatik.ausloeser()})
        if p == "/api/bots":
            return self._send(200, claude.create_bot(d))
        m = re.match(r"^/api/bots/([a-z0-9-]+)/neu$", p)
        if m:
            return self._send(200, {"sitzung": bots.neu_beginnen(m.group(1))})
        m = re.match(r"^/api/erinnerungen/([a-z0-9]+)/gezeigt$", p)
        if m:
            automatik.erinnerung_gezeigt(m.group(1))
            return self._send(200, {"erinnerungen": automatik.erinnerungen()})
        m = re.match(r"^/api/routinen/([a-z0-9]+)/jetzt$", p)
        if m:
            r = next((x for x in automatik.routinen() if x["id"] == m.group(1)), None)
            if not r:
                raise KeyError("Routine gibt es nicht.")
            automatik.run_routine(r)
            return self._send(200, {"ok": True, "session": "rt" + r["id"]})
        if p == "/api/gedaechtnis":
            e = gedaechtnis.merken(str(d.get("text") or ""), str(d.get("fuer") or "global"), "app", "app")
            return self._send(200, e)
        m = re.match(r"^/api/sessions/([a-z0-9-]+)/anhang$", p)
        if m:
            a = wissen.anhang_speichern(m.group(1), str(d.get("name") or ""), str(d.get("b64") or ""), str(d.get("ocr") or ""))
            return self._send(200, dict(a, zeile=wissen.anhang_zeile(a)))
        m = re.match(r"^/api/sessions/([a-z0-9-]+)/ablauf$", p)
        if m:
            sitzungen.send(m.group(1), basis.n("Speicher das, was wir in diesem Gespräch gemacht haben, als Ablauf (ablauf_vorschlagen) – so, dass du ihn "
                               "später ohne das Gespräch wieder ausführen kannst." + (f" Hinweis von %NUTZER%: {d['hinweis']}" if d.get("hinweis") else "")))
            return self._send(200, {"ok": True})
        m = re.match(r"^/api/ablaeufe/([a-z0-9]+)/jetzt$", p)
        if m:
            a = gedaechtnis.ablauf(m.group(1))
            b = bots.bot(str(d.get("bot") or (a["fuer"] if a["fuer"] != "global" else bots.kapitaen()["id"])))
            if not b:
                raise KeyError("Bot gibt es nicht.")
            sid = bots.bot_session(b["id"])
            sitzungen.send(sid, gedaechtnis.ablauf_text(a) + (f" {d['zusatz']}" if d.get("zusatz") else ""))
            return self._send(200, {"ok": True, "sitzung": sid})
        m = re.match(r"^/api/ablaeufe/([a-z0-9]+)/planen$", p)
        if m:
            a = gedaechtnis.ablauf(m.group(1))
            bid = str(d.get("bot") or (a["fuer"] if a["fuer"] != "global" else bots.kapitaen()["id"]))
            r = automatik.add_routine({"name": a["name"], "bot": bid, "auftrag": gedaechtnis.ablauf_text(a), "zeit": d.get("zeit"), "datum": d.get("datum"),
                             "tage": d.get("tage")})
            return self._send(200, r)
        if p == "/api/vorschlaege/durchsicht":
            threading.Thread(target=vorschlaege.durchsicht, daemon=True).start()
            return self._send(200, {"ok": True})
        m = re.match(r"^/api/vorschlaege/([a-z0-9]+)$", p)
        if m:
            return self._send(200, vorschlaege.entscheiden(m.group(1), str(d.get("entscheidung") or "").lower() == "ja"))
        m = re.match(r"^/api/freigaben/(F-[0-9a-f]{6})/rueckgaengig$", p)
        if m:
            try:
                res = home.freigaben_proxy("POST", f"/app/freigaben/{m.group(1)}/rueckgaengig", {})
            except ValueError as e:
                automatik.aktivitaet("app", "freigabe", "Zurücknehmen fehlgeschlagen", f"{m.group(1)} · {e}"[:200], ok=False)
                raise
            automatik.aktivitaet("app", "freigabe", "Zurückgenommen", f"{m.group(1)} · {(res.get('ergebnis') or '').splitlines()[-1]}"[:200])
            return self._send(200, res)
        m = re.match(r"^/api/freigaben/([A-Za-z0-9-]+)$", p)
        if m:
            res = home.freigaben_proxy("POST", f"/app/freigaben/{m.group(1)}", {"entscheidung": d.get("entscheidung")})
            f = res.get("freigabe") if isinstance(res.get("freigabe"), dict) else res
            ja = str(d.get("entscheidung") or "").lower() == "ja"
            automatik.aktivitaet(str(f.get("bot") or "app"), "freigabe", "Freigabe ausgeführt" if ja else "Freigabe abgelehnt",
                       f"{m.group(1)} · {f.get('text') or f.get('tool') or ''}"[:200], ok=not res.get("error"),
                       ref=m.group(1) if ja and f.get("status") == "ausgefuehrt" else "")
            return self._send(200, res)
        if p == "/api/diagnose":  # Protokolle der App (z. B. je Anruf: Pegel, erkannte Zeichen, Fehler – keine Inhalte)
            with basis._lock:
                lst = (basis.read_json(basis.path("diagnose.json"), []) + [dict(d, empfangen=time.strftime("%Y-%m-%d %H:%M:%S"))])[-50:]
                basis.write_json(basis.path("diagnose.json"), lst)
            return self._send(200, {"ok": True})
        if p == "/api/absturz":  # Absturzberichte der App (ohne Chat-Inhalte) – zum Nachsehen auf dem Pi
            with basis._lock:
                lst = basis.read_json(basis.path("abstuerze.json"), [])
                lst = (lst + [dict(d, empfangen=time.strftime("%Y-%m-%d %H:%M:%S"))])[-30:]
                basis.write_json(basis.path("abstuerze.json"), lst)
            basis.log("Absturzbericht der App:", d.get("geraet"), d.get("app"), [b.get("art") for b in d.get("berichte", [])])
            return self._send(200, {"ok": True})
        if p == "/api/gesundheit":
            return self._send(200, gesundheit.gesundheit_speichern(d))
        if p == "/api/anrufe/test":
            b = bots.bot(str(d.get("bot") or "")) or bots.kapitaen()
            return self._send(200, automatik.anruf_einreihen(b["id"], bots.bot_session(b["id"]),
                                                   f"Hallo {basis.nutzer()}, hier ist {b['name']}. Das ist ein Testanruf – wenn du mich hörst, klappt alles. "
                                                   "Du kannst mir jetzt direkt etwas sagen.", "Testanruf"))
        if p == "/api/arbeit":
            import base64
            daten = base64.b64decode(str(d.get("b64") or ""))
            if len(daten) > rechner.ARBEIT_MAX_DATEI:
                raise ValueError("Datei zu groß (max. 50 MB).")
            name = rechner.arbeit_name(str(d.get("name") or ""))
            with open(os.path.join(rechner.arbeit_dir(), name), "wb") as f:
                f.write(daten)
            rechner._arbeit_meta_setzen([name], "app")
            return self._send(200, {"dateien": rechner.arbeit_liste()})
        if p == "/api/diktat":
            return self._send(200, {"text": stimmen.diktat(str(d.get("b64") or ""))})
        if p == "/api/sprechen":
            data = stimmen.sprechen(str(d.get("text") or ""), str(d.get("stimme") or stimmen.STANDARD_STIMME), d.get("tempo") or 1.0)
            self.send_response(200)
            self.send_header("Content-Type", "audio/wav")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)
            return None
        if p == "/api/aussprache/anwenden":
            return self._send(200, {"text": stimmen.aussprache_anwenden(str(d.get("text") or ""))})
        if p == "/api/claude/stand":
            claude.claude_stand_setzen(d.get("usd"), d.get("datum"), d.get("aufladung_usd"), d.get("aufladung_tag"))
            return self._send(200, claude.claude_kosten())
        if p == "/api/test-push":
            dienste.push("NookBot", "Benachrichtigungen funktionieren ✓", prio="high")
            return self._send(200, {"ok": True})
        m = re.match(r"^/api/wissen/([a-z0-9-]+)$", p)
        if m:
            if d.get("b64"):
                wissen.wissen_put_datei(m.group(1), str(d.get("name") or ""), str(d.get("b64") or ""), str(d.get("ocr") or ""))
            else:
                wissen.wissen_put(m.group(1), str(d.get("name") or ""), str(d.get("text") or ""))
            return self._send(200, {"ok": True})
        raise KeyError("Gibt es nicht.")

    def do_PUT(self):  # noqa: N802
        if not self._auth(basis.pico_token()):
            return
        p, d = self.path.split("?")[0], self._body()
        try:
            m = re.match(r"^/api/bots/([a-z0-9-]+)$", p)
            if m:
                return self._send(200, claude.update_bot(m.group(1), d))
            if p == "/api/routinen":
                return self._send(200, {"routinen": automatik.set_routinen(list(d.get("routinen") or []))})
            if p == "/api/erinnerungen":
                return self._send(200, {"erinnerungen": automatik.set_erinnerungen(list(d.get("erinnerungen") or []))})
            if p == "/api/ausloeser":
                return self._send(200, {"ausloeser": automatik.set_ausloeser(list(d.get("ausloeser") or []))})
            if p == "/api/datenbanken":
                home.set_db_lib(d)
                return self._send(200, home.db_lib())
            if p == "/api/aussprache":
                neu = {str(k).strip()[:60]: str(v).strip()[:80] for k, v in (d.get("aussprache") or {}).items() if str(k).strip() and str(v).strip()}
                basis.write_json(basis.path("aussprache.json"), neu)
                return self._send(200, {"aussprache": neu})
            if p == "/api/vorschlaege":
                return self._send(200, {"einstellungen": vorschlaege.einstellungen_setzen(d)})
            if p == "/api/gedaechtnis":
                return self._send(200, {"eintraege": gedaechtnis.ged_set(list(d.get("eintraege") or []))})
            m = re.match(r"^/api/ablaeufe/([a-z0-9]+)$", p)
            if m:
                return self._send(200, gedaechtnis.ablauf_aendern(m.group(1), d))
            raise KeyError("Gibt es nicht.")
        except KeyError as e:
            return self._send(404, {"error": str(e).strip("'")})
        except Exception as e:  # noqa: BLE001
            return self._send(400, {"error": str(e)})

    def do_DELETE(self):  # noqa: N802
        if re.match(r"^/(home)?mcp/[a-z0-9-]+$", self.path.split("?")[0]):  # PicoClaw beendet seine MCP-Sitzung
            if self._auth(basis.conf()["mcp_token"]):
                self._send(200, {})
            return
        if not self._auth(basis.pico_token()):
            return
        p = urllib.parse.unquote(self.path.split("?")[0])
        try:
            m = re.match(r"^/api/sessions/([a-z0-9-]+)$", p)
            if m:
                sitzungen.SESS.delete(m.group(1))
                return self._send(200, {"ok": True})
            m = re.match(r"^/api/bots/([a-z0-9-]+)$", p)
            if m:
                claude.delete_bot(m.group(1))
                return self._send(200, {"ok": True})
            m = re.match(r"^/api/arbeit/(.+)$", p)
            if m:
                rechner.arbeit_loeschen(m.group(1))
                return self._send(200, {"dateien": rechner.arbeit_liste()})
            m = re.match(r"^/api/wissen/([a-z0-9-]+)/(.+)$", p)
            if m:
                wissen.wissen_del(m.group(1), m.group(2))
                return self._send(200, {"ok": True})
            m = re.match(r"^/api/gedaechtnis/([a-z0-9]+)$", p)
            if m:
                e = gedaechtnis.vergessen(m.group(1))
                for meta in sitzungen.SESS.index()[:60]:  # Hinweis im Chat als zurückgenommen markieren
                    s_ = sitzungen.SESS.get(meta["id"])
                    if s_ and any(x["id"] == "ged-" + e["id"] for x in s_["msgs"]):
                        sitzungen.SESS.upsert(meta["id"], {"id": "ged-" + e["id"], "zurueck": True})
                        sitzungen.SESS.save(meta["id"])
                return self._send(200, {"ok": True})
            m = re.match(r"^/api/ablaeufe/([a-z0-9]+)$", p)
            if m:
                gedaechtnis.ablauf_loeschen(m.group(1))
                return self._send(200, {"ok": True})
            raise KeyError("Gibt es nicht.")
        except KeyError as e:
            return self._send(404, {"error": str(e).strip("'")})
        except Exception as e:  # noqa: BLE001
            return self._send(400, {"error": str(e)})
