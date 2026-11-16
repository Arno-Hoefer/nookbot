import unittest

from hilfe import s, zuruecksetzen


def werkzeuge(caller):
    return {t["name"] for t in s.mcp_rpc({"id": 1, "method": "tools/list"}, caller)["result"]["tools"]}


class Mcp(unittest.TestCase):
    def setUp(self):
        zuruecksetzen()

    def test_sichtbarkeit(self):
        kap = werkzeuge(s.bot("reacher"))
        self.assertTrue({"pakete", "dokument_ansehen", "python_ausfuehren", "routine_aendern", "projekt_anlegen"} <= kap)
        self.assertFalse({"bot_anlegen", "auftraege_verteilen", "bots_liste", "bot_fragen"} & kap)  # nur noch ein Bot
        self.assertNotIn("gesundheit", kap)  # nie über „Alles“
        scout = werkzeuge(s.bot("scout"))
        self.assertFalse({"projekt_anlegen", "pakete", "gesundheit"} & scout)
        mit_ges = werkzeuge(dict(s.bot("scout"), zugriffe=True, rechte={"gesundheit": True}, merken=False))
        self.assertIn("gesundheit", mit_ges)
        self.assertNotIn("merken", mit_ges)

    def test_routine_anlegen_und_liste(self):
        r = s.mcp_rpc({"id": 2, "method": "tools/call", "params": {"name": "routine_anlegen", "arguments": {
            "name": "Morgen", "auftrag": "Was steht an?", "zeiten": ["07:45"], "tage": [1, 2, 3, 4, 5]}}}, s.bot("scout"))["result"]
        self.assertFalse(r["isError"])
        self.assertEqual([(x["name"], x["cron"]) for x in s.routinen()], [("Morgen", "45 7 * * 1,2,3,4,5")])
        r = s.mcp_rpc({"id": 3, "method": "tools/call", "params": {"name": "routine_anlegen", "arguments": {
            "name": "Kaputt", "auftrag": "x", "zeit": "25:00"}}}, s.bot("scout"))["result"]
        self.assertTrue(r["isError"])
        liste = s.mcp_rpc({"id": 4, "method": "tools/call", "params": {"name": "routinen_liste", "arguments": {}}}, s.bot("scout"))
        self.assertIn("Morgen", liste["result"]["content"][0]["text"])

    def test_routine_aendern(self):
        s.mcp_rpc({"id": 2, "method": "tools/call", "params": {"name": "routine_anlegen", "arguments": {
            "name": "Slack", "auftrag": "Prüfen", "zeiten": ["08:00"]}}}, s.bot("reacher"))
        rid = s.routinen()[0]["id"]
        r = s.mcp_rpc({"id": 5, "method": "tools/call", "params": {"name": "routine_aendern", "arguments": {
            "id": rid, "zeiten": ["08:00", "12:00", "16:00"], "tage": [1, 2, 3, 4, 5]}}}, s.bot("reacher"))["result"]
        self.assertFalse(r["isError"], r)
        self.assertEqual(s.routinen()[0]["cron"], "0 8,12,16 * * 1,2,3,4,5")
        self.assertIn("Mo–Fr · 8, 12, 16 Uhr", r["content"][0]["text"])

    def test_bot_anlegen_gibt_es_nicht_mehr(self):
        r = s.mcp_rpc({"id": 5, "method": "tools/call", "params": {"name": "bot_anlegen", "arguments": {
            "name": "Koch", "rolle": "Rezepte", "fokus": "Essen"}}}, s.bot("reacher"))
        self.assertTrue(r.get("error") or r["result"]["isError"])

    def test_ausloeser_per_mcp(self):
        r = s.mcp_rpc({"id": 6, "method": "tools/call", "params": {"name": "ausloeser_anlegen", "arguments": {
            "name": "Preis", "quelle": "webseite", "suche": "https://example.com/", "auftrag": "Sag Bescheid.", "in_diesem_chat": False}}},
            s.bot("scout"))["result"]
        self.assertFalse(r["isError"], r)
        self.assertEqual(s.ausloeser()[0]["bot"], "scout")


if __name__ == "__main__":
    unittest.main()
