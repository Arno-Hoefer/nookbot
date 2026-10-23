"""Gesundheit (Health Connect, vom Handy)."""
import json
import re
import time

from . import basis


# ── Gesundheit (Health Connect, vom Handy) ────────────────────────
# gesundheit.json: {"tage": {"YYYY-MM-DD": {...}}, "quellen": {paket: n}, "arten": [...], "stand": ts}. Nur Tageswerte, gut ein Jahr.

def gesundheit_verdichten(werte: dict) -> dict:
    """Häufige Messreihen (z. B. HRV alle 5 Minuten von Fitbit) zu Tageswerten zusammenfassen: Ø, Min, Max, Anzahl."""
    out = {}
    for k, v in werte.items():
        zahlen = [x.get("wert") for x in v if isinstance(x, dict) and isinstance(x.get("wert"), (int, float)) and not isinstance(x.get("wert"), bool)] \
            if isinstance(v, list) else []
        if isinstance(v, list) and len(v) > 6 and len(zahlen) == len(v):
            out[k] = {"avg": round(sum(zahlen) / len(zahlen), 1), "min": min(zahlen), "max": max(zahlen), "anzahl": len(zahlen)}
        else:
            out[k] = v
    return out


def gesundheit_speichern(d: dict) -> dict:
    with basis._lock:
        g = basis.read_json(basis.path("gesundheit.json"), {"tage": {}, "quellen": {}})
        for tag, werte in (d.get("tage") or {}).items():
            if re.match(r"^\d{4}-\d{2}-\d{2}$", tag) and isinstance(werte, dict):
                g["tage"][tag] = gesundheit_verdichten(werte)  # der Tag kommt immer vollständig neu
        for k in sorted(g["tage"])[:-400]:
            g["tage"].pop(k)
        for q, nq in (d.get("quellen") or {}).items():
            g["quellen"][q] = max(int(nq), int(g["quellen"].get(q, 0)))
        g["arten"] = d.get("arten") or g.get("arten", [])
        g["stand"] = time.time()
        basis.write_json(basis.path("gesundheit.json"), g)
    return gesundheit_info()


def gesundheit_info() -> dict:
    g = basis.read_json(basis.path("gesundheit.json"), {"tage": {}})
    tage = sorted(g.get("tage", {}))
    return {"tage": len(tage), "von": tage[0] if tage else "", "bis": tage[-1] if tage else "", "quellen": g.get("quellen", {}),
            "arten": g.get("arten", []), "stand": g.get("stand", 0), "tage_gesamt": len(tage)}


def gesundheit_abfrage(a: dict) -> str:
    import datetime as dt
    g = basis.read_json(basis.path("gesundheit.json"), {"tage": {}})
    if not g.get("tage"):
        return "Noch keine Gesundheitsdaten auf dem Pi – in der App unter Einstellungen → Gesundheit den Zugriff erlauben."
    heute = dt.date.today()
    try:
        bis = dt.date.fromisoformat(str(a.get("bis"))[:10]) if a.get("bis") else heute
        von = dt.date.fromisoformat(str(a.get("von"))[:10]) if a.get("von") else bis - dt.timedelta(days=int(a.get("tage") or 7) - 1)
    except ValueError:
        raise ValueError("Datum als YYYY-MM-DD.") from None
    if (bis - von).days > 400:
        von = bis - dt.timedelta(days=400)
    arten = [x.strip() for x in str(a.get("arten") or "").split(",") if x.strip()]
    out = {}
    for tag, w in sorted(g["tage"].items()):
        if von.isoformat() <= tag <= bis.isoformat():
            out[tag] = {k: v for k, v in w.items() if not arten or any(k.startswith(x) for x in arten)}
    return ("[Gesundheitsdaten aus Health Connect – vertraulich, nur für %NUTZER%s Fragen nutzen, nie ins Gedächtnis]\n"
            f"Zeitraum {von} bis {bis}, {len(out)} Tage mit Daten. Quellen: {', '.join(g.get('quellen', {}))}\n"
            "Einheiten stehen im Feldnamen (km, kcal, min, kg, l, g); Schlaf/Training/Messungen als Listen.\n\n"
            + json.dumps(out, ensure_ascii=False))
