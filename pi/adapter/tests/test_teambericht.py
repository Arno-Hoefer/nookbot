import time
import unittest

from hilfe import s, zuruecksetzen
from nookbot import sitzungen, teambericht, dienste


class Teambericht(unittest.TestCase):
    def setUp(self):
        zuruecksetzen()
        self.pushes, self.fragen = [], []
        self.alt = dienste.push, sitzungen.ask_sync
        dienste.push = lambda *a, **k: self.pushes.append((a, k))
        sitzungen.ask_sync = lambda bid, sid, text, timeout=420, as_user=True: self.fragen.append((bid, sid, text)) or self.antwort
        self.antwort = "Scout: Neue Förderung für Wärmepumpen – Antrag bis Freitag."
        self.scout = s.bot("scout")

    def tearDown(self):
        dienste.push, sitzungen.ask_sync = self.alt

    def lauf(self, text, von_dir=False):
        sid = s.bot_session("scout")
        s.SESS.start_turn(sid, "scout", von_dir=von_dir)
        s.SESS.upsert(sid, {"id": "b" + str(time.time()), "role": "bot", "text": text, "ts": time.time()})
        s.SESS.end_turn(sid)
        return sid

    def test_hintergrund_geht_an_kapitaen(self):
        self.lauf("Routine-Ergebnis: 3 neue Studien gefunden.")
        self.assertEqual(self.pushes, [])  # keine eigene Meldung des Unter-Bots
        self.assertEqual(len(teambericht._daten()["offen"]), 1)
        self.assertEqual(teambericht.zusammenfassen(), "Gemeldet.")
        bid, sid, text = self.fragen[0]
        self.assertEqual(bid, "reacher")
        self.assertIn("3 neue Studien", text)
        (titel, msg, haupt), k = self.pushes[0][0][:3], self.pushes[0][1]
        self.assertEqual((titel, k["bot"]["id"], k["tags"]), ("Reacher", "reacher", "team"))
        karten = [m for m in s.SESS.get(s.bot_session("reacher"))["msgs"] if m.get("art") == "team"]
        self.assertEqual(len(karten), 1)
        self.assertEqual(teambericht._daten()["offen"], [])

    def test_nichts_wichtiges_bleibt_still(self):
        self.lauf("Alles wie immer.")
        self.antwort = "TEAM_OK"
        self.assertEqual(teambericht.zusammenfassen(), "Nichts Wichtiges.")
        self.assertEqual(self.pushes, [])

    def test_freigabe_und_direktes_gespraech_sofort_ueber_kapitaen(self):
        self.lauf("Termin vorbereitet – Freigabe F-abc123 wartet.")
        (titel, msg), k = self.pushes[0][0][:2], self.pushes[0][1]
        self.assertEqual((titel, k["prio"]), ("Reacher", "high"))
        self.assertIn("Scout: braucht deine Freigabe", msg)
        self.lauf("Hier die Antwort auf deine Frage.", von_dir=True)
        self.assertEqual(self.pushes[1][0][0], "Reacher")
        self.assertEqual(teambericht._daten()["offen"], [])

    def test_faellig(self):
        jetzt = time.time()
        self.assertFalse(teambericht.faellig([{"ts": jetzt - 60}], jetzt, False))
        self.assertTrue(teambericht.faellig([{"ts": jetzt - 11 * 60}], jetzt, False))
        self.assertFalse(teambericht.faellig([{"ts": jetzt - 11 * 60}], jetzt, True))  # Kapitän arbeitet gerade
        self.assertTrue(teambericht.faellig([{"ts": jetzt}] * 6, jetzt, False))


if __name__ == "__main__":
    unittest.main()
