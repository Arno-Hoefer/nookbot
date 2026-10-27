import unittest

from hilfe import s, zuruecksetzen
from nookbot import claude, dienste


class ClaudeStand(unittest.TestCase):
    def setUp(self):
        zuruecksetzen()
        s.write_json(s.path("claude.json"), {"api_key": "x"})

    def test_korrektur_zaehlt_ab_jetzt(self):
        claude.claude_buchen("claude-sonnet-5-5", {"input_tokens": 1_000_000})  # Vormittag, steckt im Console-Stand schon drin
        claude.claude_stand_setzen(42.0, jetzt=True)
        self.assertEqual(claude.claude_kosten()["rest_usd"], 42.0)
        claude.claude_buchen("claude-sonnet-5-5", {"input_tokens": 1_000_000})
        k = claude.claude_kosten()
        self.assertAlmostEqual(k["rest_usd"], round(42.0 - k["seit_stand_usd"], 2))
        self.assertGreater(k["seit_stand_usd"], 0)

    def test_meldung_mit_bot(self):
        dienste.push("Reacher", "fertig", "s1", bot={"id": "reacher", "name": "Reacher", "farbe": "#FF3B4E"})
        it = s.read_json(s.path("notify.json"), {})["items"][-1]
        self.assertEqual((it["title"], it["bot"], it["farbe"]), ("Reacher", "reacher", "#FF3B4E"))


if __name__ == "__main__":
    unittest.main()
