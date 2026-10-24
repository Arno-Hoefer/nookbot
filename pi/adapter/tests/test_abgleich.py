import os
import unittest

from hilfe import s, zuruecksetzen, lies
from nookbot import bots, gedaechtnis, wissen


class Abgleich(unittest.TestCase):
    """Was ein Bot selbst an Wissen oder Ablauf-Skills ändert, muss einen Neustart (= Abgleich) überstehen."""

    def setUp(self):
        zuruecksetzen()
        self.b = next(b for b in s.bots() if b["id"] == "scout")
        os.makedirs(wissen.wissen_dir("scout"), exist_ok=True)
        os.makedirs(wissen.wissen_dir("global"), exist_ok=True)
        with open(os.path.join(wissen.wissen_dir("scout"), "kpi.md"), "w") as f:
            f.write("alt\n")
        with open(os.path.join(wissen.wissen_dir("global"), "firma.md"), "w") as f:
            f.write("global\n")
        s.write_json(s.path("ablaeufe.json"), [{"id": "a1", "name": "P0", "beschreibung": "", "schritte": "1. abfragen",
                                                 "status": "aktiv", "fuer": "scout"}])
        self.ws = bots.workspace(self.b)

    def schreib(self, rel, text, anhaengen=False):
        with open(os.path.join(self.ws, rel), "a" if anhaengen else "w", encoding="utf-8") as f:
            f.write(text)

    def test_wissen_bleibt(self):
        wissen.sync_wissen(self.b)
        self.schreib("wissen/eigen/kpi.md", "neu mit Abschnitt 0\n")
        self.schreib("wissen/eigen/p0.md", "P0-Abfrage\n")
        self.schreib("wissen/global/firma.md", "vom Bot ergänzt\n", anhaengen=True)
        wissen.sync_wissen(self.b)  # wie beim Neustart
        eigen = os.path.join(self.ws, "wissen", "eigen")
        self.assertEqual(open(os.path.join(eigen, "kpi.md")).read(), "neu mit Abschnitt 0\n")
        self.assertEqual(open(os.path.join(eigen, "p0.md")).read(), "P0-Abfrage\n")
        self.assertIn("vom Bot", open(os.path.join(eigen, "firma.md")).read())  # eigene Fassung
        self.assertEqual(open(os.path.join(wissen.wissen_dir("global"), "firma.md")).read(), "global\n")  # global unverändert

    def test_ablage_aenderung_gewinnt_ohne_botaenderung(self):
        wissen.sync_wissen(self.b)
        with open(os.path.join(wissen.wissen_dir("scout"), "kpi.md"), "w") as f:
            f.write("in der App geändert\n")
        wissen.sync_wissen(self.b)
        self.assertEqual(open(os.path.join(self.ws, "wissen", "eigen", "kpi.md")).read(), "in der App geändert\n")

    def test_ablauf_bleibt(self):
        gedaechtnis.sync_ablaeufe(self.b)
        self.schreib("skills/ablauf-a1/SKILL.md", "\n## Ersatz-SQL\nSELECT 1;\n", anhaengen=True)
        gedaechtnis.sync_ablaeufe(self.b)
        text = open(os.path.join(self.ws, "skills", "ablauf-a1", "SKILL.md")).read()
        self.assertIn("SELECT 1;", text)
        self.assertEqual(text.count("# Ablauf: P0"), 1)
        a = lies(s.path("ablaeufe.json"))[0]
        self.assertTrue(a["schritte"].startswith("1. abfragen"))
        self.assertIn("SELECT 1;", a["schritte"])
        gedaechtnis.sync_ablaeufe(self.b)  # unverändert → nichts mehr übernommen
        self.assertEqual(lies(s.path("ablaeufe.json"))[0]["schritte"], a["schritte"])


if __name__ == "__main__":
    unittest.main()
