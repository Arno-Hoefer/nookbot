"""Claude (Anthropic) neben Ollama: Proxy-Anpassung, Verbrauch, Guthaben."""
import json
import os
import re
import secrets
import shutil
import time
import urllib.error
import urllib.parse
import urllib.request

from . import basis, ereignisse


# ── Claude (Anthropic) neben Ollama ────────────────────────────────
# <BASE>/claude.json {"api_key": "sk-ant-…", "admin_key": "sk-ant-admin…" (optional, nur Kosten lesen),
#                     "stand": {"usd": 50.0, "datum": "2026-10-21"}} – angelegt mit einrichtung/7-claude.sh.
# Ein Bot mit Claude-Modell bekommt in seiner PicoClaw-Konfiguration den Eintrag „claude“ (provider anthropic).

CLAUDE_MODELLE = ["claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5", "claude-fable-5-1"]
CLAUDE_API = "https://api.anthropic.com"
# Listenpreise in $ je Million Tokens (Stand 10/2026): Eingabe, Ausgabe, Cache-Lesen. Cache-Schreiben = 1,25 × Eingabe (5 min) bzw. 2 × (1 h).
CLAUDE_PREISE = {"claude-fable-5-1": (10.0, 50.0, 0.25), "claude-opus-5-5": (4.0, 20.0, 0.20), "claude-sonnet-5-5": (2.0, 10.0, 0.20),
                 "claude-haiku-5-5": (0.10, 0.50, 0.01)}


def claude_preis(model: str, u: dict) -> float:
    """Kosten einer Antwort in $ aus ihrem usage-Block (Listenpreise; Haiku über 100K Eingabe teurer)."""
    p = next((v for k, v in CLAUDE_PREISE.items() if str(model).startswith(k)), (4.0, 20.0, 0.20))
    ein, aus, lesen = p
    cc = u.get("cache_creation") or {}
    c5, c60 = cc.get("ephemeral_5m_input_tokens"), cc.get("ephemeral_1h_input_tokens")
    if c5 is None and c60 is None:
        c5, c60 = u.get("cache_creation_input_tokens") or 0, 0
    eingabe = (u.get("input_tokens") or 0) + (u.get("cache_read_input_tokens") or 0) + (c5 or 0) + (c60 or 0)
    if str(model).startswith("claude-haiku-5-5") and eingabe > 100_000:
        ein, aus, lesen = 0.50, 2.50, 0.05
    return ((u.get("input_tokens") or 0) * ein + (u.get("output_tokens") or 0) * aus + (u.get("cache_read_input_tokens") or 0) * lesen
            + (c5 or 0) * ein * 1.25 + (c60 or 0) * ein * 2) / 1_000_000


def usage_von_openai(u: dict) -> dict:
    """usage der OpenAI-kompatiblen Schnittstelle (die PicoClaw nutzt) → Felder wie bei der Messages API."""
    cached = ((u.get("prompt_tokens_details") or {}).get("cached_tokens")) or 0
    return {"input_tokens": max(0, (u.get("prompt_tokens") or 0) - cached), "cache_read_input_tokens": cached,
            "output_tokens": u.get("completion_tokens") or 0}


def claude_anpassen(body: bytes, openai: bool = False) -> bytes:
    """PicoClaw (anthropic-sdk-go, Stand Juli 2026) schickt Angaben, die die aktuellen Modelle ablehnen – hier glätten:
    keine Sampling-Parameter, kein abgeschaltetes/budgetiertes Denken, keine erzwungene Werkzeugwahl."""
    try:
        o = json.loads(body or b"{}")
    except ValueError:
        return body
    if not isinstance(o, dict) or not str(o.get("model", "")).startswith("claude-"):
        return body
    for k in ("temperature", "top_p", "top_k"):
        o.pop(k, None)
    if openai and o.get("stream"):  # Verbrauch im letzten Block anfordern
        o["stream_options"] = {**(o.get("stream_options") if isinstance(o.get("stream_options"), dict) else {}), "include_usage": True}
    th = o.get("thinking")
    if isinstance(th, dict) and th.get("type") in ("disabled", "enabled"):
        o.pop("thinking")  # Standard ist adaptives Denken
    tc = o.get("tool_choice")
    if isinstance(tc, dict) and tc.get("type") in ("any", "tool"):
        o["tool_choice"] = {"type": "auto", **({"disable_parallel_tool_use": tc["disable_parallel_tool_use"]}
                                              if "disable_parallel_tool_use" in tc else {})}
    return json.dumps(o, ensure_ascii=False).encode()


def claude_buchen(model: str, u: dict, bid: str = ""):
    """Verbrauch einer Antwort festhalten: je Tag und Modell Tokens und Kosten (claude_verbrauch.json)."""
    usd = claude_preis(model, u)
    tag = time.strftime("%Y-%m-%d")
    with basis._lock:
        d = basis.read_json(basis.path("claude_verbrauch.json"), {})
        t = d.setdefault(tag, {}).setdefault(model or "?", {"anfragen": 0, "ein": 0, "aus": 0, "cache": 0, "usd": 0.0})
        t["anfragen"] += 1
        t["ein"] += (u.get("input_tokens") or 0) + (u.get("cache_creation_input_tokens") or 0)
        t["cache"] += u.get("cache_read_input_tokens") or 0
        t["aus"] += u.get("output_tokens") or 0
        t["usd"] = round(t["usd"] + usd, 6)
        for alt in sorted(d)[:-400]:  # gut ein Jahr reicht
            d.pop(alt)
        basis.write_json(basis.path("claude_verbrauch.json"), d)


def claude_verbrauch(seit: str) -> float:
    d = basis.read_json(basis.path("claude_verbrauch.json"), {})
    return sum(m["usd"] for tag, ms in d.items() if tag >= seit for m in ms.values())


def claude_conf() -> dict:
    return basis.read_json(basis.path("claude.json"), {})


def ist_claude(model: str) -> bool:
    return str(model or "").startswith("claude-")


def set_model(b: dict, model: str):
    cfgp = bots.bot_cfg_path(b)
    cfg = basis.read_json(cfgp, {})
    d = cfg.setdefault("agents", {}).setdefault("defaults", {})
    ml = cfg.setdefault("model_list", [])
    if ist_claude(model):
        key = claude_conf().get("api_key")
        if not key:
            raise ValueError("Claude ist auf dem Pi nicht eingerichtet (einrichtung/7-claude.sh).")
        e = next((m for m in ml if m.get("model_name") == "claude"), None)
        if not e:
            e = {"model_name": "claude", "provider": "anthropic", "request_timeout": 300, "streaming": {"enabled": True}}
            ml.append(e)
        e.update(model=model, api_keys=[key], api_base=f"http://127.0.0.1:{basis.PORT}/anthropic/v1")  # über den Adapter → Verbrauch
        d.update(model_name="claude", provider="anthropic")
    else:
        e = next((m for m in ml if m.get("provider") == "ollama" and m.get("model_name") != "flash"), None) or \
            next((m for m in ml if m.get("provider") == "ollama"), None)
        if not e:
            raise ValueError("Kein Ollama-Modell in der Konfiguration.")
        e["model"] = model
        d.update(model_name=e["model_name"], provider="ollama")
    basis.write_json(cfgp, cfg)


def claude_schluessel_verteilen():
    """Neuer oder geänderter Claude-Schlüssel: in alle Bots mit Claude-Eintrag schreiben (nach 7-claude.sh)."""
    key = claude_conf().get("api_key")
    for b in bots.bots():
        cfgp = bots.bot_cfg_path(b)
        cfg = basis.read_json(cfgp, {})
        e = next((m for m in cfg.get("model_list", []) if m.get("model_name") == "claude"), None)
        base = f"http://127.0.0.1:{basis.PORT}/anthropic/v1"
        if e and key and (e.get("api_keys") != [key] or e.get("api_base") != base):
            e.update(api_keys=[key], api_base=base)
            basis.write_json(cfgp, cfg)
            bots.restart_bot(b)


def claude_kosten() -> dict:
    """Ausgaben laut Anthropic (Admin-Schlüssel nötig): dieser Monat und seit dem eingetragenen Guthabenstand."""
    import datetime as dt
    c = claude_conf()
    out = {"eingerichtet": bool(c.get("api_key")), "kosten": bool(c.get("admin_key")), "stand": c.get("stand")}
    heute = dt.date.today()
    # Eigene Zählung (alles, was die Bots über den Adapter an Claude schicken) – geht ohne Admin-Schlüssel
    st = c.get("stand") or {}
    out["eigen_monat_usd"] = round(claude_verbrauch(heute.replace(day=1).isoformat()), 4)
    out["eigen_heute_usd"] = round(claude_verbrauch(heute.isoformat()), 4)
    auf = c.get("aufladung") or {}
    out["aufladung"] = auf or None
    if auf.get("tag"):
        out["naechste_aufladung"] = aufladungen(heute, heute + dt.timedelta(days=62), int(auf["tag"]))[0].isoformat()
    if st.get("usd") is not None and not c.get("admin_key"):
        seit = round(claude_verbrauch(str(st.get("datum"))[:10]), 4)
        out["seit_stand_usd"] = seit
        dazu = claude_aufgeladen(st, auf, heute)
        out["aufgeladen_usd"] = dazu
        out["rest_usd"] = round(float(st["usd"]) + dazu - seit, 2)
    if not c.get("admin_key"):
        return out
    monat = heute.replace(day=1)
    stand = c.get("stand") or {}
    try:
        seit = dt.date.fromisoformat(str(stand.get("datum"))[:10]) if stand.get("datum") else monat
    except ValueError:
        seit = monat
    start = min(monat, seit)

    def summe(von: dt.date) -> float:
        cents, page = 0.0, None
        while True:
            q = {"starting_at": von.isoformat() + "T00:00:00Z", "ending_at": (heute + dt.timedelta(days=1)).isoformat() + "T00:00:00Z",
                 "bucket_width": "1d", "limit": 31}
            if page:
                q["page"] = page
            req = urllib.request.Request("https://api.anthropic.com/v1/organizations/cost_report?" + urllib.parse.urlencode(q),
                                         headers={"x-api-key": c["admin_key"], "anthropic-version": "2023-06-01",
                                                  "User-Agent": "NookBot/" + basis.VERSION})
            try:
                with urllib.request.urlopen(req, timeout=20) as r:
                    o = json.loads(r.read())
            except urllib.error.HTTPError as e:
                raise ValueError(f"Anthropic-Kosten: {e.code} – Admin-Schlüssel prüfen") from None
            for bucket in o.get("data", []):
                for row in bucket.get("results", []):
                    try:
                        cents += float(row.get("amount") or 0)
                    except (TypeError, ValueError):
                        pass
            if not o.get("has_more"):
                return cents / 100
            page = o.get("next_page")

    out["monat_usd"] = round(summe(monat), 2)
    if stand.get("usd") is not None:
        seit_usd = out["monat_usd"] if seit == monat else round(summe(seit), 2)
        out["seit_stand_usd"] = seit_usd
        dazu = claude_aufgeladen(stand, auf, heute)
        out["aufgeladen_usd"] = dazu
        out["rest_usd"] = round(float(stand["usd"]) + dazu - seit_usd, 2)
    return out


def aufladungen(von, bis, tag: int) -> list:
    """Aufladetermine (Tag im Monat, im kurzen Monat der letzte) mit von < Termin <= bis."""
    import calendar
    import datetime as dt
    out, y, m = [], von.year, von.month
    while dt.date(y, m, 1) <= bis:
        d = dt.date(y, m, min(tag, calendar.monthrange(y, m)[1]))
        if von < d <= bis:
            out.append(d)
        y, m = (y + 1, 1) if m == 12 else (y, m + 1)
    return out


def claude_aufgeladen(stand: dict, auf: dict, heute) -> float:
    import datetime as dt
    if not auf.get("usd") or not auf.get("tag") or not stand.get("datum"):
        return 0.0
    try:
        von = dt.date.fromisoformat(str(stand["datum"])[:10])
    except ValueError:
        return 0.0
    return round(len(aufladungen(von, heute, int(auf["tag"]))) * float(auf["usd"]), 2)


def claude_stand_setzen(usd, datum: str | None = None, auf_usd=None, auf_tag=None):
    """Startbetrag (Guthaben an einem Tag) und optional monatliche Aufladung (Betrag an Tag X)."""
    import datetime as dt
    c = claude_conf()
    if usd in (None, ""):
        c.pop("stand", None)
    else:
        d = (datum or dt.date.today().isoformat())[:10]
        dt.date.fromisoformat(d)
        c["stand"] = {"usd": round(float(usd), 2), "datum": d}
    if auf_usd in (None, "", 0) or auf_tag in (None, "", 0):
        if auf_usd is not None or auf_tag is not None:
            c.pop("aufladung", None)
    else:
        tag = int(auf_tag)
        if not 1 <= tag <= 31:
            raise ValueError("Tag 1–31.")
        c["aufladung"] = {"usd": round(float(auf_usd), 2), "tag": tag}
    basis.write_json(basis.path("claude.json"), c)


def create_bot(d: dict) -> dict:
    name = str(d.get("name") or "").strip()[:30]
    if not name:
        raise ValueError("Name fehlt.")
    with basis._lock:
        all_b = bots.bots()
        bid = basis.slug(name)
        while any(x["id"] == bid for x in all_b):
            bid = bid[:20] + "-" + secrets.token_hex(2)
        port = max([x.get("port", 18800) for x in all_b] + [18800]) + 1
        b = {"id": bid, "name": name, "rolle": str(d.get("rolle") or "")[:40], "farbe": d.get("farbe") or "#3B8CFF",
             "form": d.get("form") if d.get("form") in bots.FORMEN else "kreis", "kapitaen": False, "zugriffe": bool(d.get("zugriffe")),
             "persoenlichkeit": str(d.get("persoenlichkeit") or "")[:3000], "fokus": str(d.get("fokus") or "")[:3000],
             "home": os.path.join(basis.BOTS_HOME, bid), "unit": f"picoclaw@{bid}.service", "port": port, "erstellt": time.time()}
        # Konfiguration aus der des Kapitäns ableiten (Modell-Zugang, Websuche, Kanal-Token)
        cfg = json.loads(json.dumps(basis.main_config()))
        cfg["gateway"].update(host="127.0.0.1", port=port)
        cfg["agents"]["defaults"]["workspace"] = bots.workspace(b)
        cfg["heartbeat"] = {"enabled": False, "interval": 30}
        cfg.setdefault("tools", {}).setdefault("mcp", {})["servers"] = {}
        os.makedirs(bots.workspace(b), mode=0o700, exist_ok=True)
        basis.write_json(bots.bot_cfg_path(b), cfg)
        if d.get("modell"):
            set_model(b, str(d["modell"]))
        all_b.append(b)
        bots.save_bots(all_b)
    bots.sync_bot(b, restart=False)
    for x in bots.bots():  # alle kennen das neue Teammitglied
        if x["id"] != b["id"]:
            bots.sync_bot(x)
    bots.unit("start", b["unit"])
    return bots.bot_public(b)


def update_bot(bid: str, d: dict) -> dict:
    with basis._lock:
        all_b = bots.bots()
        b = next((x for x in all_b if x["id"] == bid), None)
        if not b:
            raise KeyError("Bot gibt es nicht.")
        for k, n in (("name", 30), ("rolle", 40), ("persoenlichkeit", 3000), ("fokus", 3000)):
            if k in d:
                b[k] = str(d[k] or "").strip()[:n] or b.get(k)
        if "farbe" in d and re.match(r"^#[0-9A-Fa-f]{6}$", str(d["farbe"])):
            b["farbe"] = d["farbe"]
        if d.get("form") in bots.FORMEN:
            b["form"] = d["form"]
        if "rechte" in d and isinstance(d["rechte"], dict) and d["rechte"].get("alle"):
            b.pop("rechte", None)  # „Alles“: alles, was der Schlüssel darf (auch künftig Neues)
            b["zugriffe"] = True
            b["gesundheit"] = bool(d["rechte"].get("gesundheit"))  # gehört nie automatisch dazu
        elif "rechte" in d and isinstance(d["rechte"], dict):
            b["rechte"] = home.rechte_norm(d["rechte"])
            b["zugriffe"] = home.rechte_irgendwas(b["rechte"])
        elif "zugriffe" in d and not b.get("kapitaen"):
            b["zugriffe"] = bool(d["zugriffe"])
            b.pop("rechte", None)  # Schalter an = alles, aus = nichts
        if "merken" in d:
            b["merken"] = bool(d["merken"])
        if "regeln" in d and isinstance(d["regeln"], dict):
            b["regeln"] = rechner.regeln_norm(d["regeln"])
        if "stimme" in d and (not d["stimme"] or str(d["stimme"]).startswith(("pi:", "handy:", "el:"))):
            b["stimme"] = str(d["stimme"] or "")[:120]
        bots.save_bots(all_b)
    if d.get("modell"):
        set_model(b, str(d["modell"]))
    bots.sync_bot(b)
    ereignisse.EV.emit({"t": "bots"})
    if any(k in d for k in ("name", "rolle", "fokus")):
        for x in bots.bots():
            if x["id"] != b["id"]:
                bots.sync_bot(x)
    return bots.bot_public(b)


def delete_bot(bid: str):
    with basis._lock:
        all_b = bots.bots()
        b = next((x for x in all_b if x["id"] == bid), None)
        if not b or b.get("kapitaen"):
            raise ValueError("Den Kapitän kann man nicht löschen.")
        sitzungen.LINKS.close_bot(bid)
        try:
            bots.unit("stop", b["unit"])
        except ValueError:
            pass
        dst = os.path.join(basis.MAIN_HOME, "bots-geloescht", f"{bid}-{int(time.time())}")
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        if os.path.isdir(b["home"]):
            shutil.move(b["home"], dst)
        bots.save_bots([x for x in all_b if x["id"] != bid])
        wd = basis.path("wissen", bid)
        if os.path.isdir(wd):
            shutil.rmtree(wd)
        automatik.set_routinen([r for r in automatik.routinen() if r["bot"] != bid])
    for x in bots.bots():
        bots.sync_bot(x)


from . import home, bots, sitzungen, rechner, automatik  # noqa: E402
