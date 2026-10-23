import threading
import time
import unittest

from hilfe import lies, s, zuruecksetzen


class Anrufe(unittest.TestCase):
    def setUp(self):
        zuruecksetzen()

    def test_einreihen_und_abholen(self):
        a = s.anruf_einreihen("reacher", "sid1", "Guten Morgen.", "Morgen")
        self.assertEqual(a["id"], 1)
        self.assertEqual(a["name"], "Reacher")
        d = s.anrufe_warten(0, 1)
        self.assertEqual([x["id"] for x in d["anrufe"]], [1])
        t = time.time()
        self.assertEqual(s.anrufe_warten(1, 1)["anrufe"], [])
        self.assertGreaterEqual(time.time() - t, 0.9)  # wartet wirklich
        self.assertTrue(any(i.get("anruf") for i in lies(s.path("notify.json"))["items"]))  # Ausweich über die Meldungsabfrage

    def test_wartender_wird_sofort_geweckt(self):
        erg = {}
        th = threading.Thread(target=lambda: erg.update(s.anrufe_warten(0, 10)))
        t = time.time()
        th.start()
        time.sleep(0.3)
        s.anruf_einreihen("scout", "sid2", "Neu.")
        th.join(5)
        self.assertLess(time.time() - t, 3)
        self.assertEqual(erg["anrufe"][0]["bot"], "scout")


if __name__ == "__main__":
    unittest.main()
