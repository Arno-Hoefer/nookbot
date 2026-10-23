import json
import time
import unittest

from hilfe import s, zuruecksetzen


class Aktivitaet(unittest.TestCase):
    def setUp(self):
        zuruecksetzen()

    def test_eintraege_und_filter(self):
        s.aktivitaet("reacher", "werkzeug", "mails_suchen", {"suche": "x" * 300, "text": "y" * 100})
        s.aktivitaet("scout", "routine", "Morgen", "Kalender")
        s.aktivitaet("reacher", "freigabe", "Freigabe ausgeführt", "F-abc123 · Termin", ok=False, ref="F-abc123")
        alle = s.aktivitaet_liste(1)
        self.assertEqual([e["was"] for e in alle], ["Freigabe ausgeführt", "Morgen", "mails_suchen"])  # neueste zuerst
        self.assertLessEqual(len(alle[2]["detail"]), 201)  # Argumente gekürzt
        self.assertEqual([e["bot"] for e in s.aktivitaet_liste(1, bid="scout")], ["scout"])
        self.assertEqual([e["art"] for e in s.aktivitaet_liste(1, art="freigabe")], ["freigabe"])
        self.assertEqual([e["was"] for e in s.aktivitaet_liste(1, nur_hintergrund=True)], ["Morgen"])  # Routine = Hintergrund
        self.assertFalse(alle[0]["ok"])
        self.assertEqual(alle[0]["ref"], "F-abc123")

    def test_hintergrund_ueber_sitzung(self):
        s.SESS.create("scout", art="intern", sid="auftrag-scout-1")
        s.aktivitaet("scout", "werkzeug", "seite_lesen", "", sitzung="auftrag-scout-1")
        self.assertTrue(s.aktivitaet_liste(1)[0]["hintergrund"])

    def test_zeitraum_und_aufraeumen(self):
        alt = {"ts": time.time() - 40 * 86400, "bot": "reacher", "art": "werkzeug", "was": "alt", "detail": "", "sitzung": "",
               "ok": True, "hintergrund": False}
        mittel = dict(alt, ts=time.time() - 3 * 86400, was="mittel")
        with open(s.path("aktivitaet.jsonl"), "w", encoding="utf-8") as f:
            f.write(json.dumps(alt) + "\n" + json.dumps(mittel) + "\n")
        s.aktivitaet("reacher", "werkzeug", "neu")
        self.assertEqual([e["was"] for e in s.aktivitaet_liste(1)], ["neu"])
        self.assertEqual([e["was"] for e in s.aktivitaet_liste(7)], ["neu", "mittel"])
        s.aktivitaet_aufraeumen()
        with open(s.path("aktivitaet.jsonl"), encoding="utf-8") as f:
            self.assertEqual(len(f.readlines()), 2)


if __name__ == "__main__":
    unittest.main()
