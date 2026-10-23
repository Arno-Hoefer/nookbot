import threading
import unittest

from hilfe import s, zuruecksetzen


class Ausloeser(unittest.TestCase):
    def setUp(self):
        zuruecksetzen()
        self.alt = (s._eintraege, s.ask_sync, s.anruf_einreihen)
        self.stand = [("k1", "Mail 1")]
        s._eintraege = lambda a, b: list(self.stand)
        self.auftraege, self.fertig = [], threading.Event()

        def ask(bid, sid, text, timeout=420, as_user=True):
            self.auftraege.append((bid, sid, text, as_user))
            self.fertig.set()
            return self.antwort
        self.antwort = "Erledigt."
        s.ask_sync = ask
        self.anrufe = []
        s.anruf_einreihen = lambda bid, sid, text, titel="": self.anrufe.append((bid, text, titel))

    def tearDown(self):
        s._eintraege, s.ask_sync, s.anruf_einreihen = self.alt

    def anlegen(self, **x):
        a = dict({"name": "Schule", "bot": "reacher", "quelle": "mail", "suche": "from:schule@example.org", "auftrag": "Fasse zusammen."}, **x)
        return s.set_ausloeser([a])[0]

    def test_pruefung_der_eingaben(self):
        for falsch, wort in (({"quelle": "fax"}, "quelle"), ({"bot": "gibtsnicht"}, "Bot"), ({"suche": ""}, "Gmail"),
                             ({"quelle": "webseite", "suche": "example.org"}, "http"), ({"auftrag": " "}, "Auftrag")):
            with self.subTest(falsch=falsch):
                with self.assertRaises(ValueError) as cm:
                    self.anlegen(**falsch)
                self.assertIn(wort, str(cm.exception))
        a = self.anlegen(intervall=1, max_pro_tag=999, quelle="paket", suche="")
        self.assertEqual((a["intervall"], a["max_pro_tag"]), (5, 50))

    def test_stand_bleibt_nur_bei_gleicher_abfrage(self):
        a = self.anlegen()
        s.ausloeser_pruefen(a)
        a = s.ausloeser()[0]
        self.assertTrue(a["basis"])
        b = s.set_ausloeser([dict(a, auftrag="Anders")])[0]
        self.assertTrue(b["basis"])
        c = s.set_ausloeser([dict(a, suche="from:anders@example.org")])[0]
        self.assertFalse(c["basis"])
        self.assertEqual(c["gesehen"], [])

    def test_erst_stand_dann_nur_neues(self):
        a = self.anlegen()
        self.assertIn("Stand gemerkt", s.ausloeser_pruefen(a))
        self.assertEqual(self.auftraege, [])
        self.assertEqual(s.ausloeser_pruefen(s.ausloeser()[0]), "Nichts Neues.")
        self.stand = [("k2", "Mail 2"), ("k1", "Mail 1")]
        r = s.ausloeser_pruefen(s.ausloeser()[0])
        self.assertIn("neue Mail", r)
        self.assertTrue(self.fertig.wait(5))
        bid, sid, text, as_user = self.auftraege[0]
        self.assertEqual(bid, "reacher")
        self.assertFalse(as_user)
        self.assertIn("Mail 2", text)
        self.assertNotIn("Mail 1", text)
        self.assertIn("Fasse zusammen.", text)
        self.assertEqual(sid, s.bot_session("reacher"))
        self.assertTrue(any(m.get("art") == "ausloeser" for m in s.SESS.get(sid)["msgs"]))
        self.assertEqual(s.aktivitaet_liste(1, art="ausloeser")[0]["was"], "Schule")

    def test_tageslimit(self):
        a = self.anlegen(max_pro_tag=1)
        s.ausloeser_pruefen(a)
        self.stand = [("k2", "Mail 2")]
        s.ausloeser_pruefen(s.ausloeser()[0])
        self.stand = [("k3", "Mail 3")]
        self.assertEqual(s.ausloeser_pruefen(s.ausloeser()[0]), "Tageslimit erreicht.")
        self.assertTrue(self.fertig.wait(5))
        self.assertEqual(len(self.auftraege), 1)

    def test_fehler_wird_gemerkt(self):
        def kaputt(a, b):
            raise ValueError("home-mcp weg")
        s._eintraege = kaputt
        r = s.ausloeser_pruefen(self.anlegen())
        self.assertIn("home-mcp weg", r)
        self.assertIn("home-mcp weg", s.ausloeser()[0]["fehler"])

    def test_als_anruf(self):
        a = self.anlegen(anruf=True)
        s.ausloeser_pruefen(a)
        self.stand = [("k2", "Mail 2")]
        s.ausloeser_pruefen(s.ausloeser()[0])
        self.assertTrue(self.fertig.wait(5))
        self.assertIn("KEIN_ANRUF", self.auftraege[0][2])
        for _ in range(50):
            if self.anrufe:
                break
            threading.Event().wait(0.05)
        self.assertEqual(self.anrufe[0][0], "reacher")


if __name__ == "__main__":
    unittest.main()
