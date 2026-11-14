"""Projekte, Pläne und angepinnte Live-Karten (Ideen aus Claude Duet und Meta Muse).

- Projekt: eine eigene Unterhaltung (art "projekt") zu einem Thema über Wochen – mit Statuszeile fürs Menü
  („wartet auf die Hausverwaltung“). Der Kapitän legt sie an, %NUTZER% auch in der App.
- Plan: eine Checkliste im Projekt (Ziel → Punkte). Status je Punkt: offen, erledigt, beobachten, braucht_dich.
  Der Bot pflegt sie mit plan_setzen / plan_punkt, %NUTZER% hakt in der App ab. Der Rundblick sieht, was dich braucht.
- Live-Karte: eine Karte oben in einer Unterhaltung, die ein Bot aktualisiert (Pakete, Wochenplan, Kennzahlen) –
  gleicher Titel = dieselbe Karte, „aktualisiert 9:41“. Geht in jeder Unterhaltung, nicht nur in Projekten.
"""
import secrets
import time

from . import basis, ereignisse, sitzungen

STATUS = ("offen", "erledigt", "beobachten", "braucht_dich")
PLAN_MAX = 80
KARTEN_MAX = 4
WERTE_MAX = 4


def _gemeldet(sid: str):
    s = sitzungen.SESS.get(sid)
    if not s:
        return
    s["aktualisiert"] = time.time()
    sitzungen.SESS.save(sid)
    ereignisse.EV.emit({"t": "session", "s": sitzungen.SESS.meta(s)})
    ereignisse.EV.emit({"t": "sitzung", "s": sid})  # offene Unterhaltung lädt Plan und Karten neu


def anlegen(bid: str, titel: str, ziel: str = "") -> dict:
    titel = str(titel or "").strip()[:60]
    if not titel:
        raise ValueError("Titel fehlt.")
    s = sitzungen.SESS.create(bid, art="projekt", titel=titel)
    with basis._lock:
        s.update(status="", plan=[], karten=[], ziel=str(ziel or "").strip()[:300])
    _gemeldet(s["id"])
    return s


def _sitzung(sid: str) -> dict:
    s = sitzungen.SESS.get(sid or "")
    if not s:
        raise ValueError("Keine Unterhaltung – das geht nur aus einem Gespräch heraus.")
    return s


def status_setzen(sid: str, text: str) -> str:
    s = _sitzung(sid)
    if s.get("art") != "projekt":
        raise ValueError("Die Statuszeile gibt es nur in Projekten.")
    with basis._lock:
        s["status"] = str(text or "").strip()[:80]
    _gemeldet(sid)
    return f"Status: {s['status'] or '(leer)'}"


def plan_setzen(sid: str, punkte: list, ziel: str = "") -> str:
    """Neuer Plan; Punkte mit gleichem Text behalten ihren Stand (so kann der Bot den Plan ergänzen, ohne Erledigtes zu verlieren)."""
    s = _sitzung(sid)
    alt = {p["text"].lower(): p for p in s.get("plan") or []}
    neu = []
    for x in (punkte or [])[:PLAN_MAX]:
        text = str(x.get("text") if isinstance(x, dict) else x).strip()[:160]
        if not text:
            continue
        p = alt.get(text.lower()) or {"id": "p" + secrets.token_hex(3), "text": text, "status": "offen", "notiz": ""}
        if isinstance(x, dict) and x.get("faellig"):
            p["faellig"] = str(x["faellig"])[:10]
        neu.append(p)
    with basis._lock:
        s["plan"] = neu
        if ziel:
            s["ziel"] = str(ziel).strip()[:300]
    _gemeldet(sid)
    return f"Plan mit {len(neu)} Punkten gespeichert. " + zusammenfassung(s)


def plan_punkt(sid: str, pid: str, status: str = "", notiz: str | None = None) -> str:
    s = _sitzung(sid)
    p = next((x for x in s.get("plan") or [] if x["id"] == pid or x["text"].lower() == str(pid).lower()), None)
    if not p:
        raise ValueError("Diesen Punkt gibt es im Plan nicht – siehe plan_setzen.")
    with basis._lock:
        if status:
            if status not in STATUS:
                raise ValueError("status: " + ", ".join(STATUS))
            p["status"] = status
        if notiz is not None:
            p["notiz"] = str(notiz).strip()[:200]
    _gemeldet(sid)
    return f"„{p['text']}“: {p['status']}. " + zusammenfassung(s)


def zusammenfassung(s: dict) -> str:
    plan = s.get("plan") or []
    if not plan:
        return ""
    z = {st: sum(1 for p in plan if p["status"] == st) for st in STATUS}
    teile = [f"{len(plan)} Punkte", f"{z['erledigt']} erledigt"]
    if z["beobachten"]:
        teile.append(f"{z['beobachten']} beobachte ich")
    if z["braucht_dich"]:
        teile.append(f"{z['braucht_dich']} braucht dich")
    return " · ".join(teile)


def karte(sid: str, titel: str, inhalt: str = "", werte: list | None = None) -> str:
    s = _sitzung(sid)
    titel = str(titel or "").strip()[:60]
    if not titel:
        raise ValueError("Titel fehlt.")
    w = [{"label": str(x.get("label", ""))[:24], "wert": str(x.get("wert", ""))[:24]} for x in (werte or []) if isinstance(x, dict)][:WERTE_MAX]
    with basis._lock:
        karten = s.setdefault("karten", [])
        k = next((x for x in karten if x["titel"].lower() == titel.lower()), None)
        if not k:
            if len(karten) >= KARTEN_MAX:
                raise ValueError(f"Höchstens {KARTEN_MAX} angepinnte Karten – nimm erst eine ab (karte_entfernen).")
            k = {"id": "k" + secrets.token_hex(3), "titel": titel}
            karten.append(k)
        k.update(inhalt=str(inhalt or "").strip()[:600], werte=w, aktualisiert=time.time(), bot=s.get("bot", ""))
    _gemeldet(sid)
    return f"Karte „{titel}“ ist angepinnt und aktualisiert."


def karte_weg(sid: str, kid_oder_titel: str) -> str:
    s = _sitzung(sid)
    vorher = len(s.get("karten") or [])
    with basis._lock:
        s["karten"] = [k for k in s.get("karten") or [] if k["id"] != kid_oder_titel and k["titel"].lower() != str(kid_oder_titel).lower()]
    if len(s["karten"]) == vorher:
        raise ValueError("Diese Karte gibt es nicht.")
    _gemeldet(sid)
    return "Karte abgenommen."


def lage() -> str:
    """Für den Rundblick: Projekte mit Punkten, die dich brauchen oder heute/überfällig sind."""
    heute = time.strftime("%Y-%m-%d")
    zeilen = []
    for meta in sitzungen.SESS.index():
        if meta.get("art") != "projekt":
            continue
        s = sitzungen.SESS.get(meta["id"]) or {}
        offen = [p for p in s.get("plan") or [] if p["status"] == "braucht_dich" or (p["status"] != "erledigt" and p.get("faellig") and p["faellig"] <= heute)]
        if offen or s.get("status"):
            zeilen.append(f"- Projekt „{s.get('titel')}“" + (f" (Status: {s['status']})" if s.get("status") else "")
                          + "".join(f"\n  · {p['text']} [{p['status']}{', fällig ' + p['faellig'] if p.get('faellig') else ''}]" for p in offen[:5]))
    return "\n".join(zeilen)
