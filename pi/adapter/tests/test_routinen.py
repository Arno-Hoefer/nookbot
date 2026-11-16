import time
import unittest

from hilfe import s, zuruecksetzen
from nookbot import automatik, sitzungen


def zeit(jahr, mon, tag, std, minute):
    return time.localtime(time.mktime((jahr, mon, tag, std, minute, 0, 0, 0, -1)))


class Cron(unittest.TestCase):
    def test_passt(self):
        mo = zeit(2026, 11, 16, 8, 0)  # Montag
        so = zeit(2026, 11, 15, 8, 0)  # Sonntag
        self.assertTrue(automatik.cron_passt("0 8,12 * * 1-5", mo))
        self.assertFalse(automatik.cron_passt("0 8,12 * * 1-5", so))
        self.assertTrue(automatik.cron_passt("0 8 * * 0", so))
        self.assertTrue(automatik.cron_passt("0 8 * * 7", so))
        self.assertTrue(automatik.cron_passt("*/30 8-18 * * *", zeit(2026, 11, 16, 9, 30)))
        self.assertFalse(automatik.cron_passt("*/30 8-18 * * *", zeit(2026, 11, 16, 9, 31)))
        self.assertTrue(automatik.cron_passt("0 8 16 * 3", mo))  # Tag ODER Wochentag
        for kaputt in ("0 25 * * *", "x * * * *", "0 8 * *", "*/0 * * * *"):
            with self.assertRaises(ValueError):
                automatik.cron_parse(kaputt)

    def test_aus_und_zurueck(self):
        c = automatik.cron_aus(["12:00", "08:00"], [1, 2, 3, 4, 5])
        self.assertEqual(c, "0 8,12 * * 1,2,3,4,5")
        self.assertEqual(automatik.cron_zeiten(c), (["08:00", "12:00"], [1, 2, 3, 4, 5]))
        self.assertEqual(automatik.cron_aus(["06:45"], []), "45 6 * * *")
        self.assertEqual(automatik.cron_aus(["09:30"], [7]), "30 9 * * 0")
        self.assertEqual(automatik.cron_zeiten("30 9 * * 0"), (["09:30"], [7]))
        self.assertEqual(automatik.cron_aus(["07:00"], [], "2026-12-24"), "0 7 24 12 *")
        with self.assertRaises(ValueError):
            automatik.cron_aus(["08:00", "08:30"], [])
        self.assertEqual(automatik.cron_text("0 8,10,12 * * 1-5"), "Mo–Fr · 8, 10, 12 Uhr")
        self.assertEqual(automatik.cron_text("45 6 * * *"), "täglich · 6:45")
        self.assertEqual(automatik.cron_zeiten("*/30 8-18 * * *"), ([], []))
        self.assertEqual(automatik.cron_text("*/30 8-18 * * *"), "Zeitplan */30 8-18 * * *")


class Artikel(unittest.TestCase):
    def setUp(self):
        zuruecksetzen()
        self.r = automatik.add_routine({"name": "Slack", "bot": "reacher", "auftrag": "Prüfen", "zeit": "08:00", "tage": [1, 2, 3, 4, 5]})
        self.r2 = automatik.add_routine({"name": "Profit", "bot": "reacher", "auftrag": "P0", "cron": "0 9,12 * * *"})

    def test_alte_form_wird_cron(self):
        self.assertEqual(self.r["cron"], "0 8 * * 1,2,3,4,5")
        self.assertEqual(self.r["zeiten"], ["08:00"])

    def test_ergebnis_gelesen_verlauf(self):
        sid = automatik.routine_sitzung(self.r["id"])
        sitzungen.SESS.create("reacher", art="routine", sid=sid, titel="Slack")
        self.assertIsNone(automatik.routinen_public()[0]["ergebnis"])
        automatik.routine_fertig(sid, "Nichts Neues.", None, False)
        p = automatik.routinen_public()[0]
        self.assertEqual(p["ergebnis"]["text"], "Nichts Neues.")
        self.assertFalse(p["gelesen"])
        self.assertEqual(p["beschreibung"], "Mo–Fr · 8 Uhr")
        automatik.gelesen(self.r["id"])
        self.assertTrue(automatik.routinen_public()[0]["gelesen"])
        time.sleep(0.01)
        n = automatik.routine_fertig(sid, "Anna wartet auf F-1a2b3c", None, False)
        self.assertTrue(n["freigabe"])
        p = automatik.routinen_public()[0]
        self.assertFalse(p["gelesen"])  # neuer Stand → wieder sichtbar
        self.assertEqual([v["text"] for v in p["verlauf"]], ["Nichts Neues."])

    def test_reihenfolge_und_loeschen(self):
        automatik.reihenfolge([self.r2["id"], self.r["id"]])
        self.assertEqual([x["name"] for x in automatik.routinen()], ["Profit", "Slack"])
        sid = automatik.routine_sitzung(self.r2["id"])
        sitzungen.SESS.create("reacher", art="routine", sid=sid)
        automatik.routine_fertig(sid, "~€1.000", None, False)
        automatik.set_routinen([x for x in automatik.routinen() if x["id"] != self.r2["id"]])
        self.assertNotIn(self.r2["id"], automatik.ergebnisse())

    def test_lauf_endet_als_artikel_nicht_im_chat(self):
        sid = automatik.routine_sitzung(self.r["id"])
        sitzungen.SESS.create("reacher", art="routine", sid=sid, titel="Slack")
        haupt = s.bot_session("reacher")
        vorher = len(sitzungen.SESS.get(haupt)["msgs"])
        sitzungen.SESS.start_turn(sid, "reacher")
        sitzungen.SESS.upsert(sid, {"id": "m1", "role": "bot", "text": "Zwei Threads warten auf dich.", "ts": time.time(), "bot": "reacher"})
        sitzungen.SESS.end_turn(sid)
        self.assertEqual(automatik.routinen_public()[0]["ergebnis"]["text"], "Zwei Threads warten auf dich.")
        self.assertEqual(len(sitzungen.SESS.get(haupt)["msgs"]), vorher)

    def test_fremde_sitzung_kein_artikel(self):
        self.assertIsNone(automatik.routine_fertig("b-reacher-x", "Hallo", None, False))


if __name__ == "__main__":
    unittest.main()
