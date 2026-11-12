import unittest

from hilfe import s, zuruecksetzen


class Regeln(unittest.TestCase):
    def setUp(self):
        zuruecksetzen()
        self.aufrufe = []
        self.alt = s.freigaben_proxy

        def proxy(method, p, body=None):
            self.aufrufe.append((method, p, body))
            return {"status": "ausgefuehrt", "ergebnis": "Termin eingetragen", "text": "Termin X"}
        s.freigaben_proxy = proxy
        self.b = {"id": "reacher", "regeln": {"termin_anlegen": "frei", "termin_gaeste": "frei", "doc_anlegen": "nie", "quatsch": "frei"}}
        self.sid = s.bot_session("reacher")
        s.SESS.start_turn(self.sid, "reacher", von_dir=True)  # %NUTZER% schreibt gerade selbst

    def tearDown(self):
        s.freigaben_proxy = self.alt

    def test_norm_und_regel(self):
        self.assertEqual(s.regeln_norm(self.b["regeln"]), {"termin_anlegen": "frei", "doc_anlegen": "nie"})
        self.assertEqual(s.regel(self.b, "termin_anlegen"), "frei")
        self.assertEqual(s.regel(self.b, "termin_gaeste"), "fragen")  # andere Menschen → immer fragen
        self.assertEqual(s.regel(self.b, "doc_anlegen"), "nie")
        self.assertEqual(s.regel(self.b, "entwurf_anlegen"), "fragen")

    def test_frei_fuehrt_aus(self):
        t = s.frei_ausfuehren(self.b, "termin_anlegen", {"titel": "x"}, "Wartet auf Freigabe F-abc123.")
        self.assertTrue(t.startswith("Ausgeführt ohne Rückfrage"))
        self.assertEqual(self.aufrufe, [("POST", "/app/freigaben/F-abc123", {"entscheidung": "ja"})])
        e = s.aktivitaet_liste(1, art="freigabe")[0]
        self.assertEqual(e["ref"], "F-abc123")

    def test_nicht_frei_bei_gaesten_fragen_oder_mehreren_ids(self):
        self.assertIsNone(s.frei_ausfuehren(self.b, "termin_anlegen", {"gaeste": "x@example.org"}, "F-abc123"))
        self.assertIsNone(s.frei_ausfuehren(self.b, "termin_anlegen", {"einladung_senden": True}, "F-abc123"))
        self.assertIsNone(s.frei_ausfuehren(self.b, "entwurf_anlegen", {}, "F-abc123"))
        self.assertIsNone(s.frei_ausfuehren(self.b, "termin_anlegen", {}, "F-abc123 und F-def456"))
        self.assertEqual(self.aufrufe, [])

    def test_tageslimit(self):
        c = s.conf()
        c["regeln_frei_max"] = 1
        s.write_json(s.path("config.json"), c)
        self.assertIsNotNone(s.frei_ausfuehren(self.b, "termin_anlegen", {}, "F-aaaaaa"))
        self.assertIsNone(s.frei_ausfuehren(self.b, "termin_anlegen", {}, "F-bbbbbb"))
        self.assertEqual(len(self.aufrufe), 1)
        self.assertIn("Tageslimit", s.aktivitaet_liste(1, art="freigabe")[0]["was"])

    def test_frei_nicht_im_hintergrund(self):
        s.SESS.start_turn(self.sid, "reacher", von_dir=False)  # Routine oder Auslöser im selben Chat
        self.assertIsNone(s.frei_ausfuehren(self.b, "termin_anlegen", {}, "F-abc123"))
        self.assertEqual(self.aufrufe, [])
        e = s.aktivitaet_liste(1, art="freigabe")[0]
        self.assertIn("nur im Gespräch", e["was"])
        self.assertTrue(e["hintergrund"])

    def test_send_merkt_wer_startet(self):
        from nookbot import sitzungen
        alt = sitzungen.LINKS.get
        sitzungen.LINKS.get = lambda *a: type("W", (), {"send_json": lambda self, o: None})()
        try:
            sitzungen.send(self.sid, "Trag mir morgen 9 Uhr Zahnarzt ein")
            self.assertTrue(s.SESS.von_dir(self.sid))
            sitzungen.send(self.sid, "[Routine „Morgens“] …", as_user=False)
            self.assertFalse(s.SESS.von_dir(self.sid))
            a = s.bot_session("scout")
            sitzungen.SESS.create("scout", art="auftrag", sid="auftrag-scout")
            sitzungen.send("auftrag-scout", "[Auftrag von Reacher] …")  # bot_fragen schickt als Nutzer, ist aber kein Gespräch
            self.assertFalse(s.SESS.von_dir("auftrag-scout"))
            self.assertTrue(a)
        finally:
            sitzungen.LINKS.get = alt

    def test_nie_sperrt_home_aufruf(self):
        b = {"id": "reacher", "zugriffe": True, "regeln": {"doc_anlegen": "nie"}}
        t, err = s.home_aufruf(b, "doc_anlegen", {})
        self.assertTrue(err)
        self.assertIn("gesperrt", t)


if __name__ == "__main__":
    unittest.main()
