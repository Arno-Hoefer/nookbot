import time
import unittest

from hilfe import s, zuruecksetzen
from nookbot import sitzungen, rundblick, dienste, home


class Rundblick(unittest.TestCase):
    def setUp(self):
        zuruecksetzen()
        self.fragen, self.antwort, self.pushes = [], "HEARTBEAT_OK", []
        self.alt = sitzungen.ask_sync, dienste.push, home.freigaben_proxy
        sitzungen.ask_sync = lambda bid, sid, text, timeout=420, as_user=True: self.fragen.append((bid, sid, text)) or self.antwort
        dienste.push = lambda *a, **k: self.pushes.append(a)
        home.freigaben_proxy = lambda *a, **k: {"freigaben": []}
        self.haupt = s.bot_session("reacher")

    def tearDown(self):
        sitzungen.ask_sync, dienste.push, home.freigaben_proxy = self.alt

    def karten(self):
        return [m for m in s.SESS.get(self.haupt)["msgs"] if m.get("art") == "rundblick"]

    def test_still_bleibt_still(self):
        self.assertEqual(rundblick.rundblick(), "Nichts zu melden.")
        self.assertEqual(self.fragen[0][0], "reacher")
        self.assertTrue(self.fragen[0][1].startswith("intern-rundblick-"))  # eigener Lauf ohne Verlauf, wie PicoClaws Heartbeat
        self.assertEqual((self.karten(), self.pushes), ([], []))

    def test_meldung_karte_push_und_bewertung(self):
        self.antwort = "Morgen 9 Uhr Termin mit Anna – ihre Mail von Montag ist noch offen."
        self.assertEqual(rundblick.rundblick(), "Gemeldet.")
        k = self.karten()
        self.assertEqual((len(k), k[0]["text"], len(self.pushes)), (1, self.antwort, 1))
        rundblick.bewerten(k[0]["ref"], "gut")
        self.assertEqual(self.karten()[0]["status"], "gut")
        rundblick.rundblick()
        self.assertIn("👍: Morgen 9 Uhr", self.fragen[-1][2])  # sieht beim nächsten Mal, was er gemeldet hat und wie es ankam
        self.assertIn("Anna", rundblick.im_chat(self.haupt))  # Antwort darauf: Meldung als Kontext
        s.SESS.upsert(self.haupt, {"id": "u1", "role": "user", "text": "danke", "ts": time.time()})
        self.assertEqual(rundblick.im_chat(self.haupt), "")

    def test_tageslimit(self):
        rundblick.einstellungen_setzen({"max_tag": 1})
        self.antwort = "Etwas Wichtiges, das er wissen sollte."
        rundblick.rundblick()
        self.assertEqual(rundblick.rundblick(), "Tageslimit erreicht.")
        self.assertEqual(len(self.fragen), 1)  # kein Modellaufruf mehr
        self.assertEqual(rundblick.rundblick(von_hand=True), "Gemeldet.")

    def test_lage_ohne_gesundheit(self):
        sid = s.bot_session("scout")
        s.SESS.upsert(sid, {"id": "b1", "role": "bot", "text": "Neue Studie zu Wärmepumpen in Mehrfamilienhäusern gefunden, Link folgt.", "ts": time.time()})
        s.SESS.save(sid)
        p = rundblick.prompt(rundblick.daten())
        self.assertIn("Scout", p)
        self.assertIn("Wärmepumpen", p)
        self.assertIn("HEARTBEAT_OK", p)
        self.assertNotIn("%NUTZER%", basis_n(p))
        bots_ = s.read_json(s.path("bots.json"), [])
        for b in bots_:
            if b["id"] == "scout":
                b.update(zugriffe=True, rechte=dict(s.RECHTE_LEER, gesundheit=True))
        s.write_json(s.path("bots.json"), bots_)
        self.assertNotIn("Wärmepumpen", rundblick.prompt(rundblick.daten()))

    def test_aufgaben_in_heartbeat_md(self):
        self.assertIn("Rundblick", rundblick.aufgaben())
        rundblick.aufgaben_setzen("- Sag Bescheid, wenn ein Paket ankommt.")
        self.assertIn("Paket", rundblick.prompt(rundblick.daten()))
        with open(rundblick.aufgaben_pfad(), encoding="utf-8") as f:
            self.assertIn("Paket", f.read())

    def test_faellig(self):
        e = dict(rundblick.einstellungen(), zeiten=["09:30", "18:00"])
        d = {"gelaufen": {}, "letzter": 0, "meldungen": []}
        um = lambda hm, tag=4: time.strptime(f"2026-11-0{tag} {hm}", "%Y-%m-%d %H:%M")  # 04.11.2026 = Mittwoch
        self.assertEqual(rundblick.faellig(e, d, um("09:00")), "")
        self.assertEqual(rundblick.faellig(e, d, um("09:31")), "09:30")
        self.assertEqual(rundblick.faellig(e, d, um("12:00")), "")  # zu spät, nicht nachholen
        d["gelaufen"] = {"2026-11-04": ["09:30"]}
        self.assertEqual(rundblick.faellig(e, d, um("09:40")), "")
        self.assertEqual(rundblick.faellig(dict(e, werktags=True), {"gelaufen": {}}, um("09:31", 7)), "")  # Samstag


def basis_n(t):
    from nookbot import basis
    return basis.n(t)


if __name__ == "__main__":
    unittest.main()
