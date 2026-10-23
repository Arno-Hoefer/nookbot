import unittest

from hilfe import KATALOG, s, zuruecksetzen


class Rechte(unittest.TestCase):
    def setUp(self):
        zuruecksetzen()

    def test_norm_wirft_slack_details_weg_ohne_slack(self):
        r = s.rechte_norm({"mail": ["a@example.org"], "slack": False, "slack_kanaele": ["C1"], "slack_dm": True, "gitlab": 1})
        self.assertEqual(r["mail"], ["a@example.org"])
        self.assertEqual(r["slack_kanaele"], [])
        self.assertFalse(r["slack_dm"])
        self.assertIs(r["gitlab"], True)
        self.assertEqual(set(r), set(s.RECHTE_LEER))

    def test_ohne_zugriffe_nichts(self):
        self.assertEqual(s.rechte({"zugriffe": False}), s.RECHTE_LEER)

    def test_alle_ohne_gesundheit(self):
        r = s.rechte({"zugriffe": True})
        self.assertTrue(r["alle"])
        self.assertFalse(r["gesundheit"])  # Gesundheit gehört nie zu „Alles“
        self.assertEqual(r["mail"], KATALOG["konten"]["mail"])
        self.assertTrue(s.rechte({"zugriffe": True, "gesundheit": True})["gesundheit"])

    def test_teilmenge(self):
        alle = s.rechte({"zugriffe": True})
        nur_mail = s.rechte_norm({"mail": ["a@example.org"]})
        self.assertTrue(s.rechte_teilmenge(nur_mail, alle))
        self.assertFalse(s.rechte_teilmenge(alle, nur_mail))
        self.assertFalse(s.rechte_teilmenge(s.rechte_norm({"gesundheit": True}), alle))
        self.assertFalse(s.rechte_teilmenge(s.rechte_norm({"mail": ["b@example.org"]}), nur_mail))
        haupt = s.rechte_norm({"kalender": ["a@example.org"], "nur_haupt": True})
        voll = s.rechte_norm({"kalender": ["a@example.org"]})
        self.assertFalse(s.rechte_teilmenge(voll, haupt))
        self.assertTrue(s.rechte_teilmenge(haupt, voll))
        k1 = s.rechte_norm({"slack": True, "slack_kanaele": ["C1"]})
        k12 = s.rechte_norm({"slack": True, "slack_kanaele": ["C1", "C2"]})
        alle_kanaele = s.rechte_norm({"slack": True})
        self.assertTrue(s.rechte_teilmenge(k1, k12))
        self.assertFalse(s.rechte_teilmenge(k12, k1))
        self.assertFalse(s.rechte_teilmenge(alle_kanaele, k1))

    def test_home_erlaubt(self):
        r = s.rechte_norm({"kalender": ["a@example.org"], "slack": True, "db": ["heroku"]})
        self.assertTrue(s.home_erlaubt(r, "konten"))
        self.assertTrue(s.home_erlaubt(r, "termin_anlegen"))
        self.assertTrue(s.home_erlaubt(r, "termine_anlegen"))
        self.assertFalse(s.home_erlaubt(r, "mails_suchen"))
        self.assertTrue(s.home_erlaubt(r, "slack_suche"))
        self.assertFalse(s.home_erlaubt(r, "slack_fuer_mich"))  # braucht Direktnachrichten
        self.assertTrue(s.home_erlaubt(r, "db_abfrage"))
        self.assertFalse(s.home_erlaubt(r, "gitlab_issues"))
        self.assertFalse(s.home_erlaubt(r, "statistik"))
        self.assertFalse(s.home_erlaubt(r, "irgendwas"))

    def test_home_aufruf_sperrt_und_beschraenkt(self):
        aufrufe = []
        alt = s._home_roh
        s._home_roh = lambda tool, args: (aufrufe.append((tool, dict(args))) or ("ok", False))
        try:
            b = {"id": "x", "zugriffe": True, "rechte": {"kalender": ["a@example.org"], "nur_haupt": True}}
            t, err = s.home_aufruf(b, "mails_suchen", {})
            self.assertTrue(err)
            t, err = s.home_aufruf(b, "termine", {"kalender": "fremd@example.org"})
            self.assertTrue(err)
            t, err = s.home_aufruf(b, "termine", {})
            self.assertFalse(err)
            self.assertEqual(aufrufe[-1][1]["kalender"], "primary")
        finally:
            s._home_roh = alt


if __name__ == "__main__":
    unittest.main()
