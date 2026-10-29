import datetime as dt
import os
import time
import unittest

from hilfe import s, zuruecksetzen, lies
from nookbot import aufraeumen, gedaechtnis, sitzungen, vorschlaege, wissen

TAG = 86400


def eintrag(eid, text, fuer, tage_alt=0):
    return {"id": eid, "text": text, "fuer": fuer, "ts": time.time() - tage_alt * TAG, "von": "app", "quelle": ""}


class Aufraeumen(unittest.TestCase):
    def setUp(self):
        zuruecksetzen()
        self.gesendet, self.gefragt = [], []
        self.alt_send, self.alt_modell = sitzungen.send, aufraeumen.modell_fragen
        sitzungen.send = lambda sid, text, as_user=True: self.gesendet.append((sid, text))
        aufraeumen.modell_fragen = lambda prompt: self.gefragt.append(prompt) or "[]"

    def tearDown(self):
        sitzungen.send, aufraeumen.modell_fragen = self.alt_send, self.alt_modell

    def test_muster_ohne_modell(self):
        heute = dt.date.today()
        vorbei = (heute - dt.timedelta(days=10)).strftime("%d.%m.%Y")
        gedaechtnis.ged_set([
            eintrag("g1", "Mag kurze Antworten ohne Floskeln", "global", 40),
            eintrag("g2", "Mag kurze Antworten ohne Floskeln.", "scout", 5),           # doppelt mit global
            eintrag("g3", "Lieferant Müller liefert immer dienstags", "scout", 30),
            eintrag("g4", "Lieferant Müller liefert immer dienstags!", "scout", 3),         # doppelt im Bot
            eintrag("g5", f"Angebot muss bis {vorbei} raus", "scout", 20),                  # abgelaufen
            eintrag("g6", "Haus gekauft am 01.05.2015", "scout", 20),                        # Tatsache, kein Termin
            eintrag("g7", "Geburtstag von Anna am 12.03.", "scout", 300),                   # wiederkehrend
            eintrag("g8", "morgen früh an die Steuer denken", "scout", 30),                 # Zeitbezug, alt
            eintrag("g9", "morgen früh an die Steuer denken bitte", "reacher", 2),          # Zeitbezug, frisch
            eintrag("g10", f"Blutdruck-Termin bis {vorbei}", "scout", 20),                  # Gesundheit: nie anfassen
        ])
        p = {x["id"]: x["grund"] for x in aufraeumen.ged_kandidaten("scout", gedaechtnis.gedaechtnis(), heute)}
        self.assertIn("für alle Bots", p["g2"])
        self.assertTrue(("g3" in p) != ("g4" in p))  # genau einer der beiden
        self.assertIn("Datum vorbei", p["g5"])
        self.assertIn("morgen", p["g8"])
        for frei in ("g6", "g7", "g10"):
            self.assertNotIn(frei, p)
        self.assertEqual(aufraeumen.ged_kandidaten("reacher", gedaechtnis.gedaechtnis(), heute), [])

    def test_vorschlag_ja_entfernt_nein_merkt(self):
        gedaechtnis.ged_set([eintrag("g1", "Notiz eins für Scout", "scout", 3), eintrag("g2", "Notiz eins für Scout!", "scout", 1),
                             eintrag("g3", "Bleibt sicher stehen, weil wichtig", "scout", 1)])
        wissen.wissen_put("scout", "a.md", "gleicher Inhalt")
        wissen.wissen_put("scout", "b.md", "gleicher Inhalt")
        v = aufraeumen.durchsicht_scope("scout")
        self.assertEqual(v["art"], "aufraeumen")
        self.assertEqual(len(v["entfernen"]), 2)
        karte = next(m for m in s.SESS.get(v["sitzung"])["msgs"] if m.get("art") == "vorschlag")
        self.assertEqual(len(karte["punkte"]), 2)
        self.assertIn("•", karte["text"])  # die App zeigt die Punkte im Text
        # im Chat antworten schließt die Aufräum-Karte nicht (sonst bekäme der Bot den „Plan“ und löschte selbst)
        self.assertEqual(vorschlaege.im_chat(v["sitzung"]), "")
        ged_k = next(p["schluessel"] for p in v["entfernen"] if p["typ"] == "ged")
        r = vorschlaege.entscheiden(v["id"], True, nur=[ged_k])
        self.assertEqual(r["entfernt"], 1)
        self.assertEqual(len([e for e in gedaechtnis.gedaechtnis() if e["fuer"] == "scout"]), 2)
        self.assertEqual(sorted(x["name"] for x in wissen.wissen_list("scout")), ["a.md", "b.md"])  # nicht angehakt → bleibt
        self.assertEqual(self.gesendet, [])  # der Bot bekommt keinen Auftrag
        self.assertIsNone(aufraeumen.durchsicht_scope("scout"))  # nicht angehakte Datei kommt nicht wieder
        self.assertIn(next(p["schluessel"] for p in v["entfernen"] if p["typ"] == "wissen"), lies(s.path("aufraeumen.json"))["behalten"])

    def test_wissen_geaendert_bleibt(self):
        wissen.wissen_put("scout", "leer.md", "   ")
        v = aufraeumen.durchsicht_scope("scout")
        wissen.wissen_put("scout", "leer.md", "jetzt mit Inhalt")
        self.assertEqual(vorschlaege.entscheiden(v["id"], True)["entfernt"], 0)
        self.assertEqual([x["name"] for x in wissen.wissen_list("scout")], ["leer.md"])

    def test_modell_sparsam_und_ohne_gesundheit(self):
        gedaechtnis.ged_set([eintrag(f"g{i}", t, "scout", 1) for i, t in enumerate(
            ("Projekt Alpha läuft über GitLab", "Kunde Beta zahlt quartalsweise", "Heute war das Wetter schön", "Berichte immer als Tabelle",
             "Ansprechpartner Gamma ist Frau Weber", "Deploy nur freitags vormittags", "Rechnungen gehen an die Buchhaltung"))]
                            + [eintrag("gx", "Schlaf war schlecht, Puls hoch", "scout", 1)])
        aufraeumen.modell_fragen = lambda prompt: self.gefragt.append(prompt) or '[{"id": "g2", "grund": "belanglos", "warum": "Smalltalk"}, {"id": "gx", "grund": "belanglos"}]'
        v = aufraeumen.durchsicht_scope("scout")
        self.assertEqual([p["id"] for p in v["entfernen"]], ["g2"])
        self.assertNotIn("Puls", self.gefragt[0])
        self.assertIsNone(aufraeumen.durchsicht_scope("scout"))  # g2 schon offen, unverändert → kein zweiter Modellaufruf
        self.assertEqual(len(self.gefragt), 1)
        # Bot mit Gesundheits-Zugriff: nie an ein Modell
        s.update_bot("scout", {"rechte": {"gesundheit": True}})
        gedaechtnis.ged_set(gedaechtnis.gedaechtnis() + [eintrag("gy", "Noch ein neuer Eintrag zum Projekt", "scout", 0)])
        aufraeumen.durchsicht_scope("scout")
        self.assertEqual(len(self.gefragt), 1)

    def test_einstellungen_und_api(self):
        self.assertEqual(aufraeumen.einstellungen(), {"aktiv": True, "tag": 7, "zeit": "18:00"})
        e = aufraeumen.einstellungen_setzen({"aktiv": False, "tag": 3, "zeit": "07:30"})
        self.assertEqual(e, {"aktiv": False, "tag": 3, "zeit": "07:30"})
        self.assertEqual(aufraeumen.einstellungen_setzen({"tag": 9, "zeit": "25:00"}), e)  # Unsinn wird ignoriert
        self.assertEqual(lies(s.path("config.json"))["aufraeumen"], e)

    def test_bot_limit_nicht_beruehrt(self):
        gedaechtnis.ged_set([eintrag("g1", "Doppelt gemerkt beim Kapitän", "reacher", 2), eintrag("g2", "Doppelt gemerkt beim Kapitän", "reacher", 1)])
        for t, a in (("Soll ich den Wochenbericht freitags automatisch schicken?", "Routine freitags 16 Uhr anlegen"),
                     ("Soll ich Rechnungsmails künftig sofort zusammenfassen?", "ausloeser_anlegen mit Gmail-Suche nach Rechnung"),
                     ("Soll ich die Lieferadresse als Wissensdatei speichern?", "Wissensdatei adressen.md anlegen")):
            vorschlaege.vorschlag_neu("reacher", t, a)
        self.assertIsNotNone(aufraeumen.durchsicht_scope("reacher"))  # Aufräumen zählt nicht zum Tageslimit der Bots
        # … und nimmt den Bots auch keinen Platz weg
        with self.assertRaises(ValueError):
            vorschlaege.vorschlag_neu("reacher", "Noch ein völlig neues Angebot heute", "Etwas völlig Neues tun und zwar sofort")


if __name__ == "__main__":
    unittest.main()
