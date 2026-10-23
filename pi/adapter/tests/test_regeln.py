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

    def test_nie_sperrt_home_aufruf(self):
        b = {"id": "reacher", "zugriffe": True, "regeln": {"doc_anlegen": "nie"}}
        t, err = s.home_aufruf(b, "doc_anlegen", {})
        self.assertTrue(err)
        self.assertIn("gesperrt", t)


if __name__ == "__main__":
    unittest.main()
