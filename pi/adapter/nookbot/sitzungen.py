"""Sitzungen (Verlauf) und Verbindungen zu den Bots (WebSocket), Senden und Warten."""
import json
import os
import re
import secrets
import threading
import time
from wsclient import WSClient

from . import basis, ereignisse, dienste, stimmen, wissen, bots


# ── Sitzungen (Verlauf) ────────────────────────────────────────────

FREIGABE_RE = re.compile(r"\bF-[0-9a-f]{6}\b")


class Sessions:
    def __init__(self):
        self.cache: dict[str, dict] = {}
        self.rt: dict[str, dict] = {}  # Laufzeit: läuft, Start, letzte Aktivität, Warter

    def index(self) -> list[dict]:
        return basis.read_json(basis.path("sessions", "index.json"), [])

    def meta(self, s: dict) -> dict:
        last = next((m for m in reversed(s["msgs"]) if m["role"] in ("bot", "user")), None)
        return {"id": s["id"], "bot": s["bot"], "art": s.get("art", "chat"), "titel": s.get("titel", ""), "aktualisiert": s.get("aktualisiert", 0),
                "teilnehmer": s.get("teilnehmer", []),
                "letzte": (last["text"][:140].replace("\n", " ") if last else ""), "laeuft": self.running(s["id"]), "ungelesen": s.get("ungelesen", 0)}

    def get(self, sid: str) -> dict | None:
        with basis._lock:
            if sid not in self.cache:
                s = basis.read_json(basis.path("sessions", f"{sid}.json"), None)
                if not s:
                    return None
                self.cache[sid] = s
            return self.cache[sid]

    def create(self, bid: str, art: str = "chat", titel: str = "", sid: str | None = None) -> dict:
        with basis._lock:
            sid = sid or (art[0] + secrets.token_hex(8))
            s = {"id": sid, "bot": bid, "art": art, "titel": titel, "erstellt": time.time(), "aktualisiert": time.time(), "msgs": []}
            self.cache[sid] = s
            self.save(sid)
            return s

    def save(self, sid: str):
        with basis._lock:
            s = self.cache.get(sid)
            if not s:
                return
            basis.write_json(basis.path("sessions", f"{sid}.json"), s)
            idx = [x for x in self.index() if x["id"] != sid] + [self.meta(s)]
            basis.write_json(basis.path("sessions", "index.json"), sorted(idx, key=lambda x: -x["aktualisiert"])[:500])
        ereignisse.EV.emit({"t": "session", "s": self.meta(s)})

    def delete(self, sid: str):
        with basis._lock:
            self.cache.pop(sid, None)
            try:
                os.remove(basis.path("sessions", f"{sid}.json"))
            except OSError:
                pass
            basis.write_json(basis.path("sessions", "index.json"), [x for x in self.index() if x["id"] != sid])
        ereignisse.EV.emit({"t": "session_del", "s": sid})

    def ids_for_bot(self, bid: str) -> list[str]:
        return [sid for sid, r in self.rt.items() if r.get("bot") == bid]

    def running(self, sid: str) -> bool:
        return bool(self.rt.get(sid, {}).get("laeuft"))

    def upsert(self, sid: str, m: dict):
        with basis._lock:
            s = self.get(sid)
            if not s:
                return
            for i, x in enumerate(s["msgs"]):
                if x["id"] == m["id"]:
                    s["msgs"][i] = {**x, **m}
                    m = s["msgs"][i]
                    break
            else:
                s["msgs"].append(m)
            s["aktualisiert"] = time.time()
        ereignisse.EV.emit({"t": "msg", "s": sid, "m": m})

    def remove_msg(self, sid: str, mid: str):
        with basis._lock:
            s = self.get(sid)
            if s:
                s["msgs"] = [x for x in s["msgs"] if x["id"] != mid]
        ereignisse.EV.emit({"t": "del", "s": sid, "id": mid})

    # ── Ablauf einer Antwort ──
    def start_turn(self, sid: str, bid: str):
        r = self.rt.setdefault(sid, {})
        r.update(laeuft=True, bot=bid, start=time.time(), last=time.time(), n0=len(self.get(sid)["msgs"]),
                 typing=False, saw_typing=False, stop_at=0)
        r.setdefault("done", threading.Event()).clear()
        ereignisse.EV.emit({"t": "busy", "s": sid, "v": True})

    def touch(self, sid: str):
        if sid in self.rt:
            self.rt[sid]["last"] = time.time()

    def last_relevant(self, sid: str) -> dict | None:
        """Letzte Nachricht dieser Antwort ohne Denkschritte – Antworttext (bot) oder Werkzeug (tool)."""
        s = self.get(sid)
        if not s:
            return None
        n0 = self.rt.get(sid, {}).get("n0", 0)
        return next((m for m in reversed(s["msgs"][n0:]) if m["role"] in ("bot", "tool")), None)

    def final_text(self, sid: str) -> str:
        s = self.get(sid)
        if not s:
            return ""
        n0 = self.rt.get(sid, {}).get("n0", 0)
        bot_msgs = [m for m in s["msgs"][n0:] if m["role"] == "bot" and m["text"].strip()]
        return bot_msgs[-1]["text"] if bot_msgs else ""

    def end_turn(self, sid: str, error: str | None = None):
        r = self.rt.get(sid)
        if not r or not r.get("laeuft"):
            return
        r["laeuft"] = False
        s = self.get(sid)
        if not s:  # inzwischen gelöscht
            r["done"].set()
            return
        if error:
            self.upsert(sid, {"id": "err-" + secrets.token_hex(3), "role": "error", "text": error, "ts": time.time(), "bot": r.get("bot")})
        if ereignisse.EV.clients == 0 and s.get("art") == "chat":
            s["ungelesen"] = s.get("ungelesen", 0) + 1
        self.save(sid)
        ereignisse.EV.emit({"t": "busy", "s": sid, "v": False})
        r["done"].set()
        b = bots.bot(r.get("bot") or "")
        txt = self.final_text(sid)
        if b and s.get("art") in ("chat", "routine"):
            fids = FREIGABE_RE.findall(txt)
            if fids:
                dienste.push(f"{b['name']}: Freigabe wartet", txt[:300], sid, prio="high", tags="warning")
            else:  # die App entscheidet, ob sie es zeigt (nicht, wenn die Unterhaltung gerade offen ist)
                dienste.push(f"{b['name']}: {s.get('titel') or 'Antwort'}", (txt or error or "fertig")[:300], sid,
                     ntfy=s.get("art") == "routine" or ereignisse.EV.clients == 0)
        for bid in list(bots._pending_restart):
            if not any(self.running(x) for x in self.ids_for_bot(bid)):
                bb = bots.bot(bid)
                if bb:
                    bots.restart_bot(bb)


SESS = Sessions()


# ── Verbindungen zu den Bots ───────────────────────────────────────

class Links:
    def __init__(self):
        self.links: dict[tuple[str, str], tuple[WSClient, float]] = {}

    def get(self, bid: str, sid: str) -> WSClient:
        with basis._lock:
            cur = self.links.get((bid, sid))
            if cur and not cur[0].closed.is_set():
                self.links[(bid, sid)] = (cur[0], time.time())
                return cur[0]
            b = bots.bot(bid)
            if not b:
                raise KeyError("Bot gibt es nicht.")
            ws = WSClient(f"ws://127.0.0.1:{b['port']}/pico/ws?session_id={sid}", {"Authorization": "Bearer " + basis.pico_token()},
                          on_text=lambda t, s=sid, bi=bid: on_bot_event(bi, s, t),
                          on_close=lambda s=sid: on_link_closed(s))
            last_err = None
            for _ in range(10):  # Bot kann gerade neu starten
                try:
                    ws.connect()
                    break
                except Exception as e:  # noqa: BLE001
                    last_err = e
                    time.sleep(1.5)
            else:
                raise ValueError(f"{b['name']} ist nicht erreichbar ({last_err}).")
            self.links[(bid, sid)] = (ws, time.time())
            return ws

    def close_bot(self, bid: str):
        with basis._lock:
            for k in [k for k in self.links if k[0] == bid]:
                self.links.pop(k)[0].close()

    def cleanup(self):
        with basis._lock:
            for k, (ws, t) in list(self.links.items()):
                if time.time() - t > 600 and not SESS.running(k[1]):
                    self.links.pop(k)
                    ws.close()


LINKS = Links()


def on_link_closed(sid: str):
    if SESS.running(sid):
        # Verbindung weg, während der Bot arbeitet → kurz warten, dann als Fehler beenden (Wachhund)
        SESS.rt[sid]["lost"] = time.time()


def tool_names(p: dict) -> str:
    names = []
    for c in p.get("tool_calls") or []:
        n = ((c or {}).get("function") or {}).get("name")
        if n:
            names.append(n)
    return ",".join(dict.fromkeys(names)) or "werkzeug"


def on_bot_event(bid: str, sid: str, raw: str):
    try:
        o = json.loads(raw)
    except ValueError:
        return
    t, p = o.get("type"), o.get("payload") or {}
    SESS.touch(sid)
    mid = p.get("message_id")
    kind = str(p.get("kind") or "").lower()
    role = "thought" if kind == "thought" or p.get("thought") is True else "tool" if kind == "tool_calls" else "bot"
    if t == "message.create" and mid:
        text = p.get("content") or (tool_names(p) if role == "tool" else "")
        if role == "tool":  # MCP-Werkzeuge protokollieren die Endpunkte selbst (mit Argumenten)
            for tn in tool_names(p).split(","):
                if tn and not tn.startswith("mcp_") and tn != "werkzeug":
                    automatik.aktivitaet(bid, "werkzeug", tn, "", sitzung=sid)
        SESS.upsert(sid, {"id": mid, "role": role, "text": text, "ts": time.time(), "bot": bid})
    elif t == "message.update" and mid:
        m = {"id": mid}
        if p.get("content"):
            m["text"] = p["content"]
        if kind:
            m["role"] = role
        s = SESS.get(sid)
        if s and any(x["id"] == mid for x in s["msgs"]):
            SESS.upsert(sid, m)
    elif t == "message.delete" and mid:
        SESS.remove_msg(sid, mid)
    elif t == "typing.start":
        if not SESS.running(sid):
            SESS.start_turn(sid, bid)
        SESS.rt[sid].update(typing=True, saw_typing=True, stop_at=0)
    elif t == "typing.stop":
        # Kein sicheres Ende: PicoClaw hört auch zwischendurch auf zu „tippen“ (Werkzeuge, letzter Text kommt danach).
        if sid in SESS.rt:
            SESS.rt[sid].update(typing=False, stop_at=time.time())
    elif t == "error":
        SESS.end_turn(sid, error=str(p.get("message") or p.get("code") or "Fehler"))


def send(sid: str, text: str, as_user: bool = True, zusatz: str = "") -> None:
    """zusatz: Kontext nur für den Bot (steht nicht im Verlauf), z. B. offene Vorschläge, auf die sich %NUTZER% bezieht."""
    s = SESS.get(sid)
    if not s:
        raise KeyError("Unterhaltung gibt es nicht.")
    text = text.strip() if as_user else basis.n(text.strip())  # Aufträge vom Adapter: %NUTZER% durch den Namen ersetzen
    if not text:
        raise ValueError("Leere Nachricht.")
    ws = LINKS.get(s["bot"], sid)
    if as_user:
        if not s.get("titel"):
            s["titel"] = text.splitlines()[0][:60]
        SESS.upsert(sid, {"id": "u" + secrets.token_hex(5), "role": "user", "text": text, "ts": time.time()})
    s["ungelesen"] = 0
    SESS.start_turn(sid, s["bot"])
    SESS.save(sid)
    ws.send_json({"type": "message.send", "id": "m" + secrets.token_hex(4), "session_id": sid, "payload": {"content": text + zusatz}})


def ask_sync(bid: str, sid: str, text: str, timeout: int = 420, as_user: bool = True) -> str:
    """Nachricht schicken und auf die fertige Antwort warten (Runden, Routinen, bot_fragen)."""
    send(sid, text, as_user=as_user)
    done = SESS.rt[sid]["done"]
    if not done.wait(timeout):
        SESS.end_turn(sid, error="Zeitüberschreitung")
    return SESS.final_text(sid)


def turn_finished(sid: str, r: dict, now: float) -> bool:
    """Fertig, wenn die letzte Nachricht Antworttext ist (kein Werkzeug) und eine Weile nichts mehr kam."""
    if r.get("runde"):
        return False  # Runden beendet ihr eigener Ablauf
    lr = SESS.last_relevant(sid)
    if not lr or lr["role"] != "bot" or not lr["text"].strip():
        return False
    quiet = now - r.get("last", now)
    if r.get("saw_typing"):
        return not r.get("typing") and r.get("stop_at") and quiet >= 3
    return quiet >= 8  # Bot schickt keine Tipp-Ereignisse


def watchdog():
    n = 0
    while True:
        time.sleep(2)
        n += 1
        now = time.time()
        for sid, r in list(SESS.rt.items()):
            if not r.get("laeuft"):
                continue
            quiet = now - r.get("last", now)
            if turn_finished(sid, r, now):
                SESS.end_turn(sid)
            elif r.get("lost") and now - r["lost"] > 20:
                r.pop("lost", None)
                SESS.end_turn(sid, error="Verbindung zum Bot abgebrochen – bitte noch einmal fragen.")
            elif quiet > 150 and SESS.final_text(sid):
                SESS.end_turn(sid)
            elif now - r.get("start", now) > 1200:
                SESS.end_turn(sid, error="Keine Antwort nach 20 Minuten – abgebrochen.")
        if n % 15 == 0:
            try:
                gedaechtnis.ged_pruefen()
            except Exception as e:  # noqa: BLE001
                basis.log("Gedächtnis prüfen:", e)
        if n % 30 == 0:
            LINKS.cleanup()
        if n % 1800 == 0:
            wissen.anhaenge_aufraeumen()
            automatik.aktivitaet_aufraeumen()
        if n % 60 == 0:
            stimmen.piper_aufraeumen()


from . import gedaechtnis, automatik  # noqa: E402
