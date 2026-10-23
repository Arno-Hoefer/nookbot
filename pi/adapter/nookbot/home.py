"""home-mcp: Datenbank-Bibliothek, Aufrufe, Freigaben-Proxy, Zugriffe je Bot."""
import json
import os
import re
import shutil
import time
import urllib.error
import urllib.parse
import urllib.request

from . import basis


# ── Datenbank-Bibliothek (aus der App) ─────────────────────────────

def db_lib() -> dict:
    return basis.read_json(basis.path("datenbanken.json"), {"datenbanken": []})


def sync_db_skill(b: dict):
    sk = os.path.join(bots.workspace(b), "skills", "datenbanken")
    lib = db_lib().get("datenbanken", [])
    if not rechte(b)["db"] or not lib:
        if os.path.isdir(sk):
            shutil.rmtree(sk)
        return
    out = ["---\nname: datenbanken\ndescription: Beschreibung, Schema-Notizen und feste SQL-Abfragen der Datenbanken (über db_abfrage). "
           "Bei Fragen zu Kennzahlen, Zahlen oder Prognosen zuerst hier nachsehen.\n---\n",
           "# Datenbanken\n\nNutze diese Abfragen mit `db_abfrage` (Parameter `datenbank` = Name in Klammern). Du darfst Zeitraum, Filter und "
           "Gruppierung anpassen. Neue Abfragen schlägst du %NUTZER% vor. Rechnen lässt du SQL.\n"]
    for d in lib:
        out.append(f"\n## {d.get('titel') or d.get('name')} (`{d.get('name')}`)\n")
        if d.get("beschreibung"):
            out.append(d["beschreibung"].strip() + "\n")
        if d.get("notizen"):
            out.append("### Schema-Notizen\n\n" + d["notizen"].strip() + "\n")
        for q in d.get("abfragen", []):
            ps = ", ".join(f"`:{p.get('name')}` (Standard {p.get('standard') or '–'})" for p in q.get("parameter", []))
            out.append(f"### {q.get('name')}\n\n{(q.get('zweck') or '').strip()}\n" + (f"\nParameter: {ps}\n" if ps else "")
                       + f"\n```sql\n{(q.get('sql') or '').strip()}\n```\n")
    bots.write_text(os.path.join(sk, "SKILL.md"), basis.n("\n".join(out)))


def set_db_lib(d: dict):
    if not isinstance(d.get("datenbanken"), list):
        raise ValueError("datenbanken fehlt")
    basis.write_json(basis.path("datenbanken.json"), {"datenbanken": d["datenbanken"]})
    for b in bots.bots():
        bots.sync_bot(b)


# ── home-mcp (mit dem Bot-Schlüssel des Kapitäns) ──────────────────

def mcp_call(tool: str, args: dict | None = None, method: str = "tools/call") -> str:
    key = basis.homemcp_key()
    if not key:
        raise ValueError("Kein home-mcp-Schlüssel eingerichtet.")
    body = {"jsonrpc": "2.0", "id": 1, "method": method, "params": {"name": tool, "arguments": args or {}} if method == "tools/call" else {}}
    req = urllib.request.Request(basis.HOME_MCP, data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json", "Authorization": "Bearer " + key})
    with urllib.request.urlopen(req, timeout=120) as r:
        o = json.loads(r.read())
    if "error" in o:
        raise ValueError(o["error"].get("message"))
    if method != "tools/call":
        return json.dumps(o["result"])
    return "\n".join(c.get("text", "") for c in o["result"].get("content", []))


def freigaben_proxy(method: str, p: str, body: dict | None = None) -> dict:
    """Freigaben von home-mcp für die Web-Oberfläche (der App-Schlüssel liegt nur hier auf dem Pi)."""
    key = basis.conf().get("freigabe_key")
    if not key:
        raise ValueError("Freigaben im Browser nicht eingerichtet (freigabe_key in config.json).")
    req = urllib.request.Request(basis.HOME_MCP.rsplit("/mcp", 1)[0] + p, method=method, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json", "Authorization": "Bearer " + key})
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        try:
            msg = json.loads(e.read()).get("error") or ""
        except ValueError:
            msg = ""
        raise ValueError(msg or f"home-mcp antwortet mit {e.code}") from None


# ── Zugriffe je Bot (ein home-mcp-Schlüssel, Feinsteuerung hier) ──
# bot["rechte"] = {"mail": [konto], "kalender": [konto], "nur_haupt": bool, "drive": [konto], "slack": bool,
#                  "slack_kanaele": [id] (leer = alle, die home-mcp erlaubt), "slack_dm": bool, "gitlab": bool, "db": [name],
#                  "statistik": bool, "pakete": bool}
# Fehlt „rechte“: zugriffe=True → alles, was der Schlüssel darf (wie bisher); False → nichts.

RECHTE_LEER = {"mail": [], "kalender": [], "nur_haupt": False, "drive": [], "slack": False, "slack_kanaele": [], "slack_dm": False,
               "gitlab": False, "db": [], "statistik": False, "pakete": False, "gesundheit": False}
_katalog: dict = {"t": 0}


def katalog(neu: bool = False) -> dict:
    """Was der eine Schlüssel überhaupt darf: Konten je Art, Datenbanken, Slack-Kanäle, GitLab, Statistik (5 min gemerkt)."""
    if not neu and time.time() - _katalog.get("t", 0) < 300 and "konten" in _katalog:
        return _katalog
    k = {"konten": {"mail": [], "kalender": [], "drive": []}, "db": [], "gitlab": False, "statistik": False, "slack": False,
         "kanaele": [], "t": time.time()}
    if basis.homemcp_key():
        try:
            tools = {t["name"] for t in json.loads(mcp_call("", method="tools/list")).get("tools", [])}
            for line in mcp_call("konten").splitlines():
                m = re.match(r"^- ([^\s:]+@[^\s:]+): (.+)$", line.strip())
                if m:
                    for art in ("mail", "kalender", "drive"):
                        if art in m.group(2):
                            k["konten"][art].append(m.group(1))
                m = re.match(r"^- Datenbank „([^“]+)“", line.strip())
                if m:
                    k["db"].append(m.group(1))
            k["gitlab"] = any(t.startswith("gitlab_") for t in tools)
            k["statistik"] = "statistik" in tools
            k["slack"] = any(t.startswith("slack_") for t in tools)
            if k["slack"]:
                for line in mcp_call("slack_kanaele", {"anzahl": 500}).splitlines():
                    m = re.match(r"^([#@].+?) · id ([A-Z0-9]+)", line)
                    if m:
                        # @person = Direktnachricht, #mpdm-… = Gruppen-Direktnachricht – beides zählt als „Direktnachrichten“
                        k["kanaele"].append({"label": m.group(1), "id": m.group(2), "dm": m.group(1).startswith(("@", "#mpdm-"))})
        except Exception as e:  # noqa: BLE001
            basis.log("Zugriffs-Katalog:", e)
    _katalog.clear()
    _katalog.update(k)
    return k


def rechte_alle() -> dict:
    k = katalog()
    return {"mail": list(k["konten"]["mail"]), "kalender": list(k["konten"]["kalender"]), "nur_haupt": False,
            "drive": list(k["konten"]["drive"]), "slack": k["slack"], "slack_kanaele": [], "slack_dm": k["slack"],
            "gitlab": k["gitlab"], "db": list(k["db"]), "statistik": k["statistik"], "pakete": True, "gesundheit": False, "alle": True}


def rechte_norm(r: dict) -> dict:
    out = dict(RECHTE_LEER)
    for key, leer in RECHTE_LEER.items():
        v = r.get(key, leer)
        out[key] = [str(x) for x in v][:200] if isinstance(leer, list) and isinstance(v, list) else bool(v) if isinstance(leer, bool) else leer
    if not out["slack"]:
        out["slack_kanaele"], out["slack_dm"] = [], False
    return out


def rechte(b: dict) -> dict:
    if not b.get("zugriffe"):
        return dict(RECHTE_LEER)
    if not isinstance(b.get("rechte"), dict):
        return dict(rechte_alle(), gesundheit=bool(b.get("gesundheit")))
    return rechte_norm(b["rechte"])


def rechte_irgendwas(r: dict) -> bool:
    return any(bool(v) for k, v in r.items() if k not in ("nur_haupt", "slack_dm", "slack_kanaele", "alle"))


def rechte_teilmenge(a: dict, b: dict) -> bool:
    """Darf a höchstens, was b darf? (Für Aufträge zwischen Bots.)"""
    if a.get("gesundheit") and not b.get("gesundheit"):
        return False  # Gesundheit gehört nicht zu „alle“
    if b.get("alle"):
        return True
    for key in ("mail", "kalender", "drive", "db"):
        if not set(a[key]) <= set(b[key]):
            return False
    for key in ("gitlab", "statistik", "pakete", "slack", "slack_dm", "gesundheit"):
        if a.get(key) and not b.get(key):
            return False
    if a["kalender"] and b["nur_haupt"] and not a["nur_haupt"]:
        return False
    if a["slack"] and b["slack_kanaele"] and (not a["slack_kanaele"] or not set(a["slack_kanaele"]) <= set(b["slack_kanaele"])):
        return False
    return True


def rechte_text(r: dict) -> str:
    if r.get("alle"):
        return "alles, was home-mcp freigibt (Mail, Kalender, Drive, Slack, GitLab, Datenbanken, Statistik) und Pakete" + \
            ("; Gesundheitsdaten" if r.get("gesundheit") else "")
    t = []
    for key, name in (("mail", "Mail"), ("kalender", "Kalender"), ("drive", "Drive/Docs/Sheets")):
        if r[key]:
            t.append(f"{name} ({', '.join(r[key])})" + (" – nur Hauptkalender" if key == "kalender" and r["nur_haupt"] else ""))
    if r["slack"]:
        labels = {c["id"]: c["label"] for c in katalog()["kanaele"]}
        t.append("Slack (" + (", ".join(labels.get(i, i) for i in r["slack_kanaele"]) if r["slack_kanaele"] else "alle freigegebenen Kanäle")
                 + (", Direktnachrichten/Erwähnungen" if r["slack_dm"] else ", keine Direktnachrichten") + ")")
    if r["gitlab"]:
        t.append("GitLab")
    if r["db"]:
        t.append("Datenbank " + ", ".join(r["db"]))
    if r["statistik"]:
        t.append("Besucherstatistik")
    if r["pakete"]:
        t.append("Pakete")
    if r.get("gesundheit"):
        t.append("Gesundheitsdaten (Health Connect)")
    return "; ".join(t) or "keine"


ART = {**{t: "mail" for t in ("mails_suchen", "mail_lesen", "thread_lesen", "entwurf_anlegen")},
       **{t: "kalender" for t in ("kalender_liste", "termine", "termin_anlegen", "termine_anlegen", "termin_gaeste")},
       **{t: "drive" for t in ("drive_suche", "drive_lesen", "sheets_lesen", "sheets_schreiben", "sheets_anhaengen", "sheet_anlegen",
                               "doc_anlegen", "doc_anhaengen")}}
MEHRERE_KONTEN = {"mails_suchen", "kalender_liste", "termine", "drive_suche"}  # ohne konto: alle Konten


def home_erlaubt(r: dict, tool: str) -> bool:
    if tool in ("konten", "freigabe_status"):
        return True
    if tool in ART:
        return bool(r[ART[tool]])
    if tool.startswith("slack_"):
        return r["slack"] and (tool != "slack_fuer_mich" or r["slack_dm"])
    if tool.startswith("gitlab_"):
        return r["gitlab"]
    if tool.startswith("db_"):
        return bool(r["db"])
    if tool == "statistik":
        return r["statistik"]
    return False


def _konto_waehlen(erlaubt: list, wunsch: str) -> list:
    if not wunsch:
        return erlaubt
    m = [k for k in erlaubt if wunsch.lower() in k.lower()]
    if not m:
        raise ValueError(f"Konto „{wunsch}“ ist für dich nicht freigegeben. Erlaubt: {', '.join(erlaubt)}")
    return m[:1]


def _kanal(k: str) -> dict | None:
    k = str(k or "").strip().lstrip("#")
    for c in katalog()["kanaele"]:
        if c["id"] == k or c["label"].lstrip("#").lower() == k.lower() or c["label"].lower() == k.lower():
            return c
    return None


def home_aufruf(b: dict, tool: str, args: dict) -> tuple[str, bool]:
    """Ein home-mcp-Werkzeug für diesen Bot ausführen – mit seinen Grenzen und Regeln. Gibt (Text, Fehler) zurück."""
    if tool in rechner.REGEL_WERKZEUGE and rechner.regel(b, tool) == "nie":
        return f"{rechner.REGEL_WERKZEUGE[tool]} ist für dich gesperrt (App → Bot → Regeln).", True
    t, err = _home_aufruf_grenzen(b, tool, args)
    if not err and tool in rechner.REGEL_WERKZEUGE:
        frei = rechner.frei_ausfuehren(b, tool, args or {}, t)
        if frei:
            return frei, False
    return t, err


def _home_aufruf_grenzen(b: dict, tool: str, args: dict) -> tuple[str, bool]:
    r = rechte(b)
    if not home_erlaubt(r, tool):
        return f"{tool} ist für dich nicht freigegeben (App → Bot → Zugriffe).", True
    if r.get("alle"):
        return _home_roh(tool, args)
    if tool == "konten":
        return "Deine Zugriffe: " + rechte_text(r) + ".", False
    args = dict(args or {})
    if tool in ART:
        alle_konten = katalog()["konten"][ART[tool]]
        konten = _konto_waehlen(r[ART[tool]], str(args.get("konto") or ""))
        if tool in ("kalender_liste", "termine", "termin_anlegen", "termine_anlegen", "termin_gaeste") and r["nur_haupt"]:
            if args.get("kalender") and str(args["kalender"]) not in ("primary",) + tuple(konten):
                return "Für dich ist nur der Hauptkalender freigegeben.", True
            if tool != "kalender_liste":
                args["kalender"] = "primary"
        if set(konten) == set(alle_konten) and not args.get("konto") and not r["nur_haupt"]:
            return _home_roh(tool, args)
        if tool in MEHRERE_KONTEN or len(konten) == 1:
            teile, fehler = [], True
            for k in konten:
                t, err = _home_roh(tool, dict(args, konto=k))
                teile.append(t)
                fehler = fehler and err
            return "\n\n".join(teile), fehler
        letzter = ("", True)
        for k in konten:  # Datei-id: das Konto nehmen, das sie sieht
            letzter = _home_roh(tool, dict(args, konto=k))
            if not letzter[1]:
                return letzter
        return letzter
    if tool.startswith("db_"):
        db = str(args.get("datenbank") or "")
        if not db:
            if len(r["db"]) > 1:
                return f"datenbank angeben: {', '.join(r['db'])}", True
            args["datenbank"] = r["db"][0]
        elif db not in r["db"]:
            return f"Datenbank „{db}“ ist für dich nicht freigegeben. Erlaubt: {', '.join(r['db'])}", True
        return _home_roh(tool, args)
    if tool.startswith("slack_"):
        nur = set(r["slack_kanaele"])
        if tool in ("slack_verlauf", "slack_thread"):
            c = _kanal(str(args.get("kanal") or ""))
            if c is None:
                return _home_roh(tool, args) if not nur and r["slack_dm"] else (f"Kanal „{args.get('kanal')}“ unbekannt oder nicht freigegeben.", True)
            if c["dm"] and not r["slack_dm"]:
                return "Direktnachrichten sind für dich nicht freigegeben.", True
            if not c["dm"] and nur and c["id"] not in nur:
                return f"{c['label']} ist für dich nicht freigegeben.", True
            return _home_roh(tool, args)
        t, err = _home_roh(tool, args)
        if err or (not nur and r["slack_dm"]):
            return t, err
        namen = {c["label"] for c in katalog()["kanaele"] if c["id"] in nur} if nur else {c["label"] for c in katalog()["kanaele"] if not c["dm"]}
        zeilen = t.splitlines()
        if tool == "slack_kanaele":
            behalten = [z for z in zeilen[1:] if any(z.startswith(nm + " · ") for nm in namen)]
        else:  # slack_suche: Zeilen beginnen mit „#kanal · “
            behalten = [z for z in zeilen[1:] if any(z.startswith(nm + " · ") for nm in namen)]
        return "\n".join(zeilen[:1] + (behalten or ["Nichts in deinen freigegebenen Kanälen."])), False
    return _home_roh(tool, args)


def _home_roh(tool: str, args: dict) -> tuple[str, bool]:
    key = basis.homemcp_key()
    body = {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": tool, "arguments": args}}
    req = urllib.request.Request(basis.HOME_MCP, data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json", "Authorization": "Bearer " + key})
    try:
        with urllib.request.urlopen(req, timeout=180) as resp:
            o = json.loads(resp.read())
    except Exception as e:  # noqa: BLE001
        return f"home-mcp nicht erreichbar: {e}", True
    if "error" in o:
        return str(o["error"].get("message")), True
    res = o.get("result") or {}
    return "\n".join(c.get("text", "") for c in res.get("content", [])), bool(res.get("isError"))


def homemcp_rpc(msg: dict, b: dict):
    """MCP-Endpunkt /homemcp/<bot>: wie home-mcp, aber nur mit den Werkzeugen und Grenzen dieses Bots."""
    mid, method = msg.get("id"), msg.get("method")
    if mid is None:
        return None
    ok = lambda res: {"jsonrpc": "2.0", "id": mid, "result": res}  # noqa: E731
    if method in ("initialize", "ping"):
        key = basis.homemcp_key()
        req = urllib.request.Request(basis.HOME_MCP, data=json.dumps(msg).encode(), method="POST",
                                     headers={"Content-Type": "application/json", "Authorization": "Bearer " + key})
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read())
    if method == "tools/list":
        r = rechte(b)
        tools = json.loads(mcp_call("", method="tools/list")).get("tools", [])
        return ok({"tools": [t for t in tools if home_erlaubt(r, t["name"]) and rechner.regel(b, t["name"]) != "nie"]})
    if method == "tools/call":
        p = msg.get("params") or {}
        t, err = home_aufruf(b, str(p.get("name") or ""), p.get("arguments") or {})
        automatik.aktivitaet(b["id"], "werkzeug", str(p.get("name") or ""), p.get("arguments") or {}, sitzung=mcp.caller_session(b["id"]) or "", ok=not err)
        return ok({"content": [{"type": "text", "text": basis.n(t)}], "isError": err})
    return {"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": "Methode unbekannt"}}


from . import bots, rechner, automatik, mcp  # noqa: E402
