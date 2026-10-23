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

def push(title: str, msg: str, sid: str | None = None, prio: str = "default", tags: str = "robot", ntfy: bool = True):
    """Benachrichtigung: in den Meldungs-Speicher (die App holt ihn alle paar Minuten ab) und – falls eingerichtet – über ntfy."""
    with basis._lock:
        d = basis.read_json(basis.path("notify.json"), {"last": 0, "items": []})
        d["last"] += 1
        d["items"] = (d["items"] + [{"id": d["last"], "ts": time.time(), "title": title, "text": msg[:500], "s": sid, "prio": prio}])[-100:]
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
