"""Ollama (Modelle, Guthaben) und Benachrichtigungen (ntfy)."""
import json
import time
import urllib.error
import urllib.parse
import urllib.request

from . import basis


# ── Ollama ─────────────────────────────────────────────────────────

def ollama_get(url: str) -> dict:
    base, key = basis.ollama()
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + key})
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read())


def modelle() -> list[str]:
    base, _ = basis.ollama()
    return sorted(m["id"] for m in ollama_get(base + "/models").get("data", []))


# ── Benachrichtigungen (ntfy) ──────────────────────────────────────

# Welche Meldungen aufs Handy kommen (App → Einstellungen → Meldungen) und wann Ruhe ist. Freigaben kommen immer (sie warten auf dich).
THEMEN = {"antworten": "Antworten im Chat", "routinen": "Neue Routinen-Ergebnisse", "rundblick": "Rundblick", "vorschlaege": "Vorschläge"}
_THEMA = {"compass": "rundblick", "bulb": "vorschlaege", "routine": "routinen"}


def meldungen() -> dict:
    m = basis.conf().get("meldungen") or {}
    return {"themen": {k: bool(m.get("themen", {}).get(k, True)) for k in THEMEN}, "namen": THEMEN,
            "ruhe_an": bool(m.get("ruhe_an")), "ruhe_von": m.get("ruhe_von") or "21:30", "ruhe_bis": m.get("ruhe_bis") or "07:00"}


def meldungen_setzen(d: dict) -> dict:
    alt = meldungen()
    neu = {"themen": {k: bool((d.get("themen") or {}).get(k, alt["themen"][k])) for k in THEMEN},
           "ruhe_an": bool(d.get("ruhe_an", alt["ruhe_an"]))}
    for k in ("ruhe_von", "ruhe_bis"):
        v = str(d.get(k) or alt[k])
        neu[k] = v if len(v) == 5 and v[2] == ":" and v[:2].isdigit() and v[3:].isdigit() else alt[k]
    with basis._lock:
        c = basis.conf()
        c["meldungen"] = neu
        basis.write_json(basis.path("config.json"), c)
    return meldungen()


def _stumm(prio: str, tags: str) -> bool:
    if prio == "high":
        return False  # Freigaben und Tests kommen immer
    m = meldungen()
    if not m["themen"].get(_THEMA.get(tags, "antworten"), True):
        return True
    if m["ruhe_an"]:
        jetzt, von, bis = time.strftime("%H:%M"), m["ruhe_von"], m["ruhe_bis"]
        return (von <= jetzt or jetzt < bis) if von > bis else (von <= jetzt < bis)
    return False


def push(title: str, msg: str, sid: str | None = None, prio: str = "default", tags: str = "robot", ntfy: bool = True, bot: dict | None = None):
    """Benachrichtigung: in den Meldungs-Speicher (die App holt ihn alle paar Minuten ab) und – falls eingerichtet – über ntfy.
    bot: von wem – die App zeigt dann je Bot eine eigene Unterhaltung (Name, Farbe) statt einer gemeinsamen NookBot-Meldung.
    Abgeschaltete Themen und die Ruhezeit (App → Einstellungen → Meldungen) verschlucken die Meldung – der Chat bleibt, wie er ist."""
    if _stumm(prio, tags):
        return
    with basis._lock:
        d = basis.read_json(basis.path("notify.json"), {"last": 0, "items": []})
        d["last"] += 1
        it = {"id": d["last"], "ts": time.time(), "title": title, "text": msg[:500], "s": sid, "prio": prio}
        if bot:
            it.update(bot=bot["id"], name=bot["name"], farbe=bot.get("farbe") or "")
        d["items"] = (d["items"] + [it])[-100:]
        basis.write_json(basis.path("notify.json"), d)
    n = basis.conf().get("ntfy") or {}
    if not n.get("url") or not ntfy:
        return
    import base64
    headers = {"Title": "=?UTF-8?B?" + base64.b64encode(title.encode()).decode() + "?=", "Priority": prio, "Tags": tags}
    if sid:
        headers["Click"] = f"nookbot://chat/{sid}"
    if n.get("token"):
        headers["Authorization"] = "Bearer " + n["token"]
    try:
        req = urllib.request.Request(n["url"].rstrip("/") + "/" + n.get("topic", "nookbot"), data=msg[:3500].encode(), headers=headers, method="POST")
        urllib.request.urlopen(req, timeout=10).read()
    except Exception as e:  # noqa: BLE001
        basis.log("ntfy:", e)
