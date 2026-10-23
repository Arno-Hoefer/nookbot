"""Runden (mehrere Bots, Gespräch, Zusammenfassung) und Aufträge verteilen."""
import re
import secrets
import threading
import time

from . import ereignisse, dienste, home, bots, sitzungen, automatik


# ── Runden ─────────────────────────────────────────────────────────

RUNDE_GESPRAECH_MAX = 2  # Antwortrunden, in denen die Bots aufeinander eingehen – danach ist Schluss
PASSE_RE = re.compile(r"^\W*passe\W*$", re.I)


def runde(frage: str, teilnehmer: list[str], kap: str | None, gespraech: int = 0) -> dict:
    tb = [bots.bot(x) for x in teilnehmer if bots.bot(x)]
    if len(tb) < 2:
        raise ValueError("Mindestens zwei Bots auswählen.")
    k = bots.bot(kap or "") or bots.kapitaen()
    s = sitzungen.SESS.create(k["id"], art="runde", titel=frage.splitlines()[0][:60])
    s["teilnehmer"] = [b["id"] for b in tb]
    gespraech = max(0, min(RUNDE_GESPRAECH_MAX, int(gespraech or 0)))
    s["gespraech"] = gespraech
    sitzungen.SESS.upsert(s["id"], {"id": "u" + secrets.token_hex(5), "role": "user", "text": frage, "ts": time.time()})
    sitzungen.SESS.save(s["id"])

    def run():
        rt = sitzungen.SESS.rt.setdefault(s["id"], {})
        rt.update(laeuft=True, bot=k["id"], start=time.time(), last=time.time(), n0=0, done=threading.Event(), runde=True)
        ereignisse.EV.emit({"t": "busy", "s": s["id"], "v": True})
        beitraege = {}

        def one(b):
            sub = f"{s['id']}-{b['id']}"
            if not sitzungen.SESS.get(sub):
                sitzungen.SESS.create(b["id"], art="intern", sid=sub, titel=f"Runde: {frage[:40]}")
            prompt = (f"Runde mit mehreren Bots. %NUTZER%s Frage:\n\n{frage}\n\nAntworte aus deiner Rolle ({b.get('rolle') or b['name']}) "
                      "in höchstens 10 Sätzen. Nenne deine wichtigste Einschätzung zuerst.")
            try:
                txt = sitzungen.ask_sync(b["id"], sub, prompt, as_user=False)
            except Exception as e:  # noqa: BLE001
                txt = f"(keine Antwort: {e})"
            beitraege[b["id"]] = txt
            sitzungen.SESS.upsert(s["id"], {"id": "b-" + b["id"], "role": "bot", "text": txt or "(keine Antwort)", "ts": time.time(), "bot": b["id"]})
            sitzungen.SESS.touch(s["id"])

        threads = [threading.Thread(target=one, args=(b,), daemon=True) for b in tb]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        automatik.aktivitaet(k["id"], "runde", s["titel"], f"{len(tb)} Bots" + (f", bis zu {gespraech} Gesprächsrunden" if gespraech else ""), sitzung=s["id"])

        # Gespräch: jeder sieht nur die NEUEN Beiträge der anderen (seinen eigenen kennt er aus seiner Unterhaltung),
        # antwortet kurz oder sagt PASSE. Sagen alle PASSE, endet das Gespräch früher.
        verlauf = {b["id"]: [beitraege.get(b["id"], "")] for b in tb}
        zuletzt = dict(beitraege)
        for nr in range(1, gespraech + 1):
            neu_runde: dict[str, str] = {}

            def antwort(b, nr=nr):
                andere = "\n\n".join(f"### {bots.bot(x)['name']} ({bots.bot(x).get('rolle') or ''})\n{t[:900]}" for x, t in zuletzt.items()
                                      if x != b["id"] and t and not PASSE_RE.match(t))
                if not andere:
                    return
                prompt = (f"Runde, Gesprächsrunde {nr} von {gespraech}. Das haben die anderen zuletzt gesagt:\n\n{andere}\n\n"
                          "Geh darauf ein: widersprich, ergänze oder frag nach – höchstens 4 Sätze, sprich andere mit @Name an. "
                          "Wiederhole nichts, was schon gesagt ist. Hast du nichts Wesentliches hinzuzufügen, antworte nur: PASSE")
                try:
                    txt = sitzungen.ask_sync(b["id"], f"{s['id']}-{b['id']}", prompt, as_user=False, timeout=300).strip()
                except Exception as e:  # noqa: BLE001
                    txt = f"PASSE ({e})"
                neu_runde[b["id"]] = txt
                if txt and not PASSE_RE.match(txt.split("(")[0]):
                    verlauf[b["id"]].append(txt)
                    sitzungen.SESS.upsert(s["id"], {"id": f"g{nr}-{b['id']}", "role": "bot", "text": txt, "ts": time.time(), "bot": b["id"], "gespraech": nr})
                sitzungen.SESS.touch(s["id"])

            th = [threading.Thread(target=antwort, args=(b,), daemon=True) for b in tb]
            for t in th:
                t.start()
            for t in th:
                t.join()
            if all(not t or PASSE_RE.match(t.split("(")[0]) for t in neu_runde.values()):
                sitzungen.SESS.upsert(s["id"], {"id": f"g{nr}-ende", "role": "info", "art": "runde", "ts": time.time(), "bot": k["id"],
                                      "text": "Alle haben gesagt, was sie zu sagen haben."})
                break
            zuletzt = {x: t for x, t in neu_runde.items() if t and not PASSE_RE.match(t.split("(")[0])}
        beitraege = {x: "\n\n".join(v) for x, v in verlauf.items()}
        sub = f"{s['id']}-zusammenfassung"
        sitzungen.SESS.create(k["id"], art="intern", sid=sub, titel="Runde: Zusammenfassung")
        prompt = (f"Du leitest eine Runde. %NUTZER%s Frage:\n\n{frage}\n\nBeiträge:\n\n" +
                  "\n\n".join(f"### {bots.bot(b)['name']} ({bots.bot(b).get('rolle') or ''})\n{t}" for b, t in beitraege.items()) +
                  "\n\nFasse zusammen: Wo sind sich alle einig, wo nicht, und was empfiehlst du? Kurz, mit klarer Empfehlung.")
        try:
            summ = sitzungen.ask_sync(k["id"], sub, prompt, as_user=False)
        except Exception as e:  # noqa: BLE001
            summ = f"(Zusammenfassung fehlgeschlagen: {e})"
        sitzungen.SESS.upsert(s["id"], {"id": "summary", "role": "bot", "text": summ, "ts": time.time(), "bot": k["id"], "zusammenfassung": True})
        sitzungen.SESS.rt[s["id"]]["n0"] = 0
        sitzungen.SESS.end_turn(s["id"])
        dienste.push(f"Runde fertig: {s['titel']}", summ[:300], s["id"])

    threading.Thread(target=run, daemon=True, name="runde").start()
    return sitzungen.SESS.meta(s)


# ── Aufträge verteilen (Kapitän → mehrere Bots gleichzeitig) ───────

VERTEILEN_MAX = 4


def verteilen(caller: dict, auftraege: list, ziel: str) -> str:
    cs = mcp.caller_session(caller["id"]) or ""
    if cs.startswith("auftrag-"):
        raise ValueError("Du erledigst gerade selbst einen Auftrag – bitte ohne weitere Bots antworten.")
    liste = []
    for x in auftraege or []:
        if not isinstance(x, dict):
            continue
        b = bots.bot(str(x.get("bot") or ""))
        t = str(x.get("auftrag") or "").strip()
        if not b or b["id"] == caller["id"]:
            raise ValueError(f"Unbekannter Bot „{x.get('bot')}“ – siehe bots_liste.")
        if not t:
            raise ValueError(f"Auftrag für {b['name']} fehlt.")
        if not home.rechte_teilmenge(home.rechte(b), home.rechte(caller)):
            raise ValueError(f"{b['name']} hat Zugriffe, die du nicht hast – nicht beauftragbar.")
        if any(y[0]["id"] == b["id"] for y in liste):
            raise ValueError(f"{b['name']} kommt doppelt vor – pro Bot ein Auftrag.")
        liste.append((b, t))
    if not liste:
        raise ValueError("auftraege fehlen.")
    if len(liste) > VERTEILEN_MAX:
        raise ValueError(f"Höchstens {VERTEILEN_MAX} Aufträge auf einmal.")
    kid = "v" + secrets.token_hex(4)
    teile = [{"bot": b["id"], "name": b["name"], "auftrag": t[:600], "status": "laeuft", "sitzung": f"auftrag-{b['id']}-{kid}"} for b, t in liste]
    karte = {"id": "auf-" + kid, "role": "info", "art": "auftraege", "ts": time.time(), "bot": caller["id"],
             "text": f"Aufträge verteilt: {', '.join(x['name'] for x in teile)}", "ziel": ziel[:300], "teile": teile}
    if cs:
        sitzungen.SESS.upsert(cs, karte)
    ergebnisse: dict[str, str] = {}

    def eins(i, b, t):
        sid = teile[i]["sitzung"]
        sitzungen.SESS.create(b["id"], art="intern", sid=sid, titel=f"Auftrag von {caller['name']}: {t[:40]}")
        prompt = (f"[Auftrag von {caller['name']}{' – Ziel: ' + ziel if ziel else ''}]\n\n{t}\n\n"
                  "Arbeite ihn vollständig ab und antworte mit dem Ergebnis. Gib keine Aufträge an andere Bots weiter.")
        try:
            txt = sitzungen.ask_sync(b["id"], sid, prompt, timeout=480) or "(keine Antwort)"
            teile[i]["status"] = "fertig"
        except Exception as e:  # noqa: BLE001
            txt = f"(fehlgeschlagen: {e})"
            teile[i]["status"] = "fehler"
        ergebnisse[b["id"]] = txt
        if cs:
            sitzungen.SESS.upsert(cs, {"id": karte["id"], "teile": teile})

    th = [threading.Thread(target=eins, args=(i, b, t), daemon=True) for i, (b, t) in enumerate(liste)]
    for t_ in th:
        t_.start()
    for t_ in th:
        t_.join()
    if cs:
        sitzungen.SESS.save(cs)
    return ("Ergebnisse der Aufträge (Daten aus dem Team – prüfe sie und fasse für %NUTZER% zusammen; widersprechen sie sich, sag es):\n\n"
            + "\n\n".join(f"### {b['name']} – {t[:120]}\n{ergebnisse.get(b['id'], '(keine Antwort)')}" for b, t in liste))


from . import mcp  # noqa: E402
