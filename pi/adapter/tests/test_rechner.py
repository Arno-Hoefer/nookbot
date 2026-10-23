import os
import shutil
import socket
import sys
import unittest
from unittest import mock

from hilfe import s, zuruecksetzen


def adressen(ip):
    return lambda host, port, *a, **k: [(socket.AF_INET, socket.SOCK_STREAM, 6, "", (ip, port))]


class Rechner(unittest.TestCase):
    def setUp(self):
        zuruecksetzen()

    def test_dateinamen(self):
        self.assertEqual(s.arbeit_name("bericht.xlsx"), "bericht.xlsx")
        self.assertEqual(s.arbeit_name("../../etc/passwd"), "passwd")  # nie aus dem Ordner heraus
        for falsch in ("", "  ", ".versteckt", "a" * 121):
            with self.subTest(name=falsch):
                with self.assertRaises(ValueError):
                    s.arbeit_name(falsch)

    def test_liste_mit_herkunft(self):
        with open(os.path.join(s.arbeit_dir(), "x.csv"), "w") as f:
            f.write("a,b\n")
        s._arbeit_meta_setzen(["x.csv"], "scout")
        l = s.arbeit_liste()
        self.assertEqual([(x["name"], x["bot"]) for x in l], [("x.csv", "scout")])
        s.arbeit_loeschen("x.csv")
        self.assertEqual(s.arbeit_liste(), [])

    def test_nur_oeffentliche_seiten(self):
        with mock.patch("socket.getaddrinfo", adressen("192.168.178.1")):
            with self.assertRaises(ValueError):
                s.oeffentlich("http://router.example/")
        with mock.patch("socket.getaddrinfo", adressen("100.101.102.103")):  # Tailnet
            with self.assertRaises(ValueError):
                s.oeffentlich("https://pi.example/")
        with mock.patch("socket.getaddrinfo", adressen("127.0.0.1")):
            with self.assertRaises(ValueError):
                s.oeffentlich("http://localhost:8795/api/state")
        with self.assertRaises(ValueError):
            s.oeffentlich("file:///etc/passwd")
        with mock.patch("socket.getaddrinfo", adressen("93.184.216.34")):
            self.assertEqual(s.oeffentlich("https://example.com/x"), "https://example.com/x")

    @unittest.skipUnless(sys.platform.startswith("linux") and shutil.which("bwrap"), "bubblewrap gibt es nur auf dem Pi (Linux)")
    def test_python_ohne_netz(self):
        r = s.python_ausfuehren(s.bot("reacher"), "import urllib.request\ntry:\n urllib.request.urlopen('https://example.com', timeout=3)\n"
                                                  " print('OFFEN')\nexcept Exception:\n print('ZU')\nopen('t.txt','w').write('x')\n")
        self.assertIn("ZU", r)
        self.assertIn("t.txt", r)


if __name__ == "__main__":
    unittest.main()
