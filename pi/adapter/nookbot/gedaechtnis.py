"""Gedächtnis und Abläufe."""
import os
import re
import secrets
import shutil
import time

from . import basis, ereignisse, bots, sitzungen


# ── Gedächtnis ─────────────────────────────────────────────────────
# gedaechtnis.json: [{id, text, fuer: "global" | <bot-id>, ts, von, quelle}]. Der Adapter schreibt daraus
# memory/MEMORY.md jedes Bots (PicoClaw liest die Datei bei jeder Antwort). Ändert ein Bot die Datei selbst,
# übernimmt der Adapter neue Zeilen als Einträge (sichtbar in der App) und schreibt sie wieder sauber.

GED_MAX = 120
_ged_render: dict[str, str] = {}


def gedaechtnis() -> list[dict]:
    return basis.read_json(basis.path("gedaechtnis.json"), [])


def ged_fuer(bid: str) -> list[dict]:
    return [e for e in gedaechtnis() if e["fuer"] in ("global", bid)]


def ged_md(b: dict) -> str:
    alle = gedaechtnis()
    g = [e for e in alle if e["fuer"] == "global"]
    e_ = [e for e in alle if e["fuer"] == b["id"]]
    teile = ["# Gedächtnis\n\nVon NookBot verwaltet. Neues nur mit dem Werkzeug `merken`, Veraltetes mit `vergessen` – "
             "diese Datei nicht selbst bearbeiten. Die [ids] brauchst du für `vergessen`.\n"]
    teile.append("## Über %NUTZER% (für alle Bots)\n\n" + ("\n".join(f"- [{x['id']}] {x['text']}" for x in g) or "(noch nichts)") + "\n")
    teile.append("## Nur für dich\n\n" + ("\n".join(f"- [{x['id']}] {x['text']}" for x in e_) or "(noch nichts)") + "\n")
    return basis.n("\n".join(teile))


def ged_render(b: dict):
    md = ged_md(b)
    p_ = os.path.join(bots.workspace(b), "memory", "MEMORY.md")
    bots.write_text(p_, md)
    _ged_render[b["id"]] = md


def ged_render_alle():
    for b in bots.bots():
        try:
            ged_render(b)
        except OSError as e:
            basis.log("Gedächtnis", b["id"], e)


def ged_set(lst: list[dict]) -> list[dict]:
    ids = {b["id"] for b in bots.bots()}
    out = []
    for e in lst:
        t = " ".join(str(e.get("text") or "").split())[:400]
        if not t:
            continue
        f = str(e.get("fuer") or "global")
        out.append({"id": e.get("id") or "g" + secrets.token_hex(3), "text": t, "fuer": f if f in ids else "global",
                    "ts": e.get("ts") or time.time(), "von": e.get("von") or "app", "quelle": e.get("quelle") or ""})
    basis.write_json(basis.path("gedaechtnis.json"), out)
    ged_render_alle()
    ereignisse.EV.emit({"t": "gedaechtnis"})
    return out


def merken(text: str, fuer: str, von: str, quelle: str = "") -> dict:
    t = " ".join(str(text or "").split())[:400]
    if len(t) < 3:
        raise ValueError("text fehlt.")
    if re.search(r"(?i)(passwort|password|kennwort|\bpin\b|api[-_ ]?key|token|iban|secret|geheim)\b.{0,3}[:=]", t) or \
            re.search(r"\b(sk|xox[bp]|ghp|glpat|hmk|nb)_?[A-Za-z0-9-]{16,}", t):
        raise ValueError("Zugangsdaten und Geheimnisse werden nie gemerkt.")
    with basis._lock:
        alle = gedaechtnis()
        if any(e["text"].lower() == t.lower() and e["fuer"] == fuer for e in alle):
            raise ValueError("Steht schon im Gedächtnis.")
        if sum(1 for e in alle if e["fuer"] == fuer) >= GED_MAX:
            raise ValueError(f"Gedächtnis voll ({GED_MAX} Einträge) – erst Veraltetes mit vergessen entfernen.")
        e = {"id": "g" + secrets.token_hex(3), "text": t, "fuer": fuer, "ts": time.time(), "von": von, "quelle": quelle}
        ged_set(alle + [e])
    return e


def vergessen(eid: str, bid: str | None = None) -> dict:
    with basis._lock:
        alle = gedaechtnis()
        e = next((x for x in alle if x["id"] == eid), None)
        if not e or (bid and e["fuer"] not in ("global", bid)):
            raise ValueError("Diesen Eintrag gibt es nicht.")
        ged_set([x for x in alle if x["id"] != eid])
    return e


def ged_pruefen():
    """Hat ein Bot MEMORY.md selbst geändert? Neue Zeilen übernehmen, Datei neu schreiben."""
    for b in bots.bots():
        p_ = os.path.join(bots.workspace(b), "memory", "MEMORY.md")
        try:
            cur = open(p_, encoding="utf-8").read()
        except OSError:
            cur = ""
        if b["id"] not in _ged_render or cur == _ged_render[b["id"]]:
            continue
        known = {e["text"].lower() for e in gedaechtnis()}
        neu = []
        for line in cur.splitlines():
            m = re.match(r"^\s*[-*]\s+(?:\[[a-z0-9]+\]\s*)?(.{3,400})$", line)
            if m and not m.group(1).startswith("(") and m.group(1).lower() not in known and "NookBot verwaltet" not in line:
                neu.append(m.group(1).strip())
        for t in neu[:10]:
            try:
                e = merken(t, b["id"], b["id"], "direkt")
                sid = b.get("sitzung")
                if sid and sitzungen.SESS.get(sid):
                    ged_hinweis(sid, b["id"], e)
            except ValueError:
                pass
        ged_render(b)


def ged_hinweis(sid: str, bid: str, e: dict):
    sitzungen.SESS.upsert(sid, {"id": "ged-" + e["id"], "role": "info", "art": "gedaechtnis", "ref": e["id"], "ts": time.time(), "bot": bid,
                      "text": ("🧠 Gemerkt (alle Bots): " if e["fuer"] == "global" else "🧠 Gemerkt: ") + e["text"]})


# ── Abläufe (aus einem Gespräch gelernt, als Skill für die Bots) ───
# ablaeufe.json: [{id, name, beschreibung, schritte, fuer, status: entwurf|aktiv, von, sitzung, erstellt}]
# Ein Entwurf wird erst nach dem OK in der App zum Skill skills/ablauf-<id>/SKILL.md.

def ablaeufe() -> list[dict]:
    return basis.read_json(basis.path("ablaeufe.json"), [])


def ablauf(aid: str) -> dict:
    a = next((x for x in ablaeufe() if x["id"] == aid), None)
    if not a:
        raise KeyError("Diesen Ablauf gibt es nicht.")
    return a


def set_ablaeufe(lst: list[dict]) -> list[dict]:
    basis.write_json(basis.path("ablaeufe.json"), lst)
    for b in bots.bots():
        bots.sync_bot(b)
    ereignisse.EV.emit({"t": "ablaeufe"})
    return lst


def ablauf_neu(d: dict, von: str, sitzung: str = "") -> dict:
    name = " ".join(str(d.get("name") or "").split())[:60]
    schritte = str(d.get("schritte") or "").strip()[:8000]
    if not name or len(schritte) < 20:
        raise ValueError("name und schritte (die Anleitung, mind. ein paar Sätze) angeben.")
    a = {"id": "a" + secrets.token_hex(3), "name": name, "beschreibung": " ".join(str(d.get("beschreibung") or "").split())[:300],
         "schritte": schritte, "fuer": "global" if d.get("fuer_alle") else von, "status": "entwurf", "von": von,
         "sitzung": sitzung, "erstellt": time.time()}
    with basis._lock:
        basis.write_json(basis.path("ablaeufe.json"), ablaeufe() + [a])
    ereignisse.EV.emit({"t": "ablaeufe"})
    return a


def ablauf_aendern(aid: str, d: dict) -> dict:
    with basis._lock:
        lst = ablaeufe()
        a = next((x for x in lst if x["id"] == aid), None)
        if not a:
            raise KeyError("Diesen Ablauf gibt es nicht.")
        for k, mx in (("name", 60), ("beschreibung", 300), ("schritte", 8000)):
            if k in d and str(d[k] or "").strip():
                a[k] = str(d[k]).strip()[:mx]
        if "fuer" in d and (d["fuer"] == "global" or bots.bot(str(d["fuer"]))):
            a["fuer"] = d["fuer"]
        if d.get("status") in ("entwurf", "aktiv"):
            a["status"] = d["status"]
        set_ablaeufe(lst)
    if a.get("sitzung") and sitzungen.SESS.get(a["sitzung"]):
        sitzungen.SESS.upsert(a["sitzung"], {"id": "abl-" + aid, "status": a["status"], "titel": a["name"]})
        sitzungen.SESS.save(a["sitzung"])
    return a


def ablauf_loeschen(aid: str):
    with basis._lock:
        lst = ablaeufe()
        a = next((x for x in lst if x["id"] == aid), None)
        if not a:
            raise KeyError("Diesen Ablauf gibt es nicht.")
        set_ablaeufe([x for x in lst if x["id"] != aid])
    if a.get("sitzung") and sitzungen.SESS.get(a["sitzung"]):
        sitzungen.SESS.upsert(a["sitzung"], {"id": "abl-" + aid, "status": "verworfen"})
        sitzungen.SESS.save(a["sitzung"])


def sync_ablaeufe(b: dict):
    sk = os.path.join(bots.workspace(b), "skills")
    if os.path.isdir(sk):
        for d in os.listdir(sk):
            if d.startswith("ablauf-"):
                shutil.rmtree(os.path.join(sk, d), ignore_errors=True)
    for a in ablaeufe():
        if a["status"] != "aktiv" or a["fuer"] not in ("global", b["id"]):
            continue
        desc = (a.get("beschreibung") or a["name"]).replace("\n", " ")
        bots.write_text(os.path.join(sk, f"ablauf-{a['id']}", "SKILL.md"),
                   basis.n(f"---\nname: ablauf-{a['id']}\ndescription: Ablauf „{a['name']}“ – {desc}\n---\n\n# Ablauf: {a['name']}\n\n"
                     f"Von %NUTZER% bestätigt. Folge diesen Schritten, wenn %NUTZER% danach fragt („{a['name']}“) oder eine Routine "
                     f"diesen Ablauf nennt. Fehlt eine Angabe (z. B. Zeitraum), nimm den naheliegenden Standard und sag, welchen.\n\n{a['schritte']}\n"))


def ablauf_text(a: dict) -> str:
    return f"Führe den Ablauf „{a['name']}“ aus (Skill ablauf-{a['id']})."
