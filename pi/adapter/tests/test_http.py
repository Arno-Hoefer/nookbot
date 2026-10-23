import json
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

from hilfe import TOKEN, s, zuruecksetzen


class Http(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.srv = ThreadingHTTPServer(("127.0.0.1", 0), s.Handler)
        cls.srv.daemon_threads = True
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()
        cls.base = f"http://127.0.0.1:{cls.srv.server_address[1]}"

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()

    def setUp(self):
        zuruecksetzen()

    def api(self, method, p, body=None, token=TOKEN):
        r = urllib.request.Request(self.base + p, data=json.dumps(body).encode() if body is not None else None, method=method,
                                   headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(r, timeout=10) as resp:
                return resp.status, json.loads(resp.read() or b"{}")
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read() or b"{}")

    def test_ping_ohne_schluessel(self):
        with urllib.request.urlopen(self.base + "/ping", timeout=5) as r:
            self.assertEqual(json.loads(r.read())["version"], s.VERSION)

    def test_schluessel_noetig(self):
        self.assertEqual(self.api("GET", "/api/state", token="falsch")[0], 401)

    def test_state(self):
        code, o = self.api("GET", "/api/state")
        self.assertEqual(code, 200)
        self.assertEqual([b["id"] for b in o["bots"]], ["reacher", "scout"])
        for k in ("sessions", "routinen", "ausloeser", "erinnerungen", "ablaeufe", "gedaechtnis"):
            self.assertIn(k, o)
        self.assertEqual(o["bots"][0]["modell"], "glm-5.3")

    def test_ausloeser_api(self):
        code, o = self.api("PUT", "/api/ausloeser", {"ausloeser": [{"name": "P", "bot": "reacher", "quelle": "paket", "auftrag": "Sag es."}]})
        self.assertEqual(code, 200)
        code, o = self.api("GET", "/api/ausloeser")
        self.assertEqual([a["name"] for a in o["ausloeser"]], ["P"])
        self.assertIn("mail", o["quellen"])
        code, o = self.api("PUT", "/api/ausloeser", {"ausloeser": [{"name": "X", "bot": "reacher", "quelle": "mail", "auftrag": "a"}]})
        self.assertEqual(code, 400)
        self.assertIn("Gmail", o["error"])

    def test_aktivitaet_und_regeln(self):
        s.aktivitaet("reacher", "werkzeug", "termine")
        code, o = self.api("GET", "/api/aktivitaet?tage=1&bot=reacher")
        self.assertEqual((code, [e["was"] for e in o["eintraege"]]), (200, ["termine"]))
        code, o = self.api("GET", "/api/regeln")
        self.assertIn("termin_anlegen", o["werkzeuge"])
        self.assertEqual(o["nie_frei"], ["termin_gaeste"])

    def test_bot_regeln_speichern(self):
        code, o = self.api("PUT", "/api/bots/scout", {"regeln": {"termin_anlegen": "frei", "termin_gaeste": "frei"}})
        self.assertEqual(code, 200, o)
        self.assertEqual(s.bot("scout")["regeln"], {"termin_anlegen": "frei"})


if __name__ == "__main__":
    unittest.main()
