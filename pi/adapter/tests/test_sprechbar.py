import unittest

from hilfe import s


class Sprechbar(unittest.TestCase):
    def test_beispiele(self):
        for ein, aus in (
            ("Sa 14.11. Frühdienst 5, 07:30–13:30 Uhr", "Samstag, 14. November, Frühdienst 5, 7 Uhr 30 bis 13 Uhr 30"),
            ("Termin um 21:30 und um 10:00 Uhr.", "Termin um 21 Uhr 30 und um 10 Uhr."),
            ("Mi, 18.11.2026: Medis Stellen", "Mittwoch, 18. November 2026: Medis Stellen"),
            ("So machen wir das, z. B. ca. 12,50 € bzw. 15 %.", "So machen wir das, zum Beispiel circa 12,50 Euro beziehungsweise 15 Prozent."),
            ("Fr 6.11. 06:30-13:15", "Freitag, 6. November, 6 Uhr 30 bis 13 Uhr 15"),
            ("Nr. 5 am 31.12.", "Nummer 5 am 31. Dezember"),
        ):
            with self.subTest(ein=ein):
                self.assertEqual(s.sprechbar(ein), aus)

    def test_kein_datum_wird_nicht_verbogen(self):
        self.assertEqual(s.sprechbar("Version 1.2.3"), "Version 1.2.3")
        self.assertEqual(s.sprechbar("So geht das."), "So geht das.")


if __name__ == "__main__":
    unittest.main()
