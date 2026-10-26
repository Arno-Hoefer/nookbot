import os
import stat
import tempfile
import unittest

from nookbot import stimmen

ERSATZ = """#!/bin/sh
# tut so, als wäre es ptts: schreibt eine kurze WAV nach -o und merkt sich den Text
while [ $# -gt 1 ]; do [ "$1" = "-o" ] && ziel=$2; shift; done
echo "$1" > "$(dirname "$0")/letzter-text"
python3 -c "import wave,sys;w=wave.open(sys.argv[1],'wb');w.setnchannels(1);w.setsampwidth(2);w.setframerate(24000);w.writeframes(b'\\0\\0'*2400)" "$ziel"
"""


class Phonon(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp(prefix="phonon-")
        os.makedirs(os.path.join(self.d, "stimmen"))
        os.makedirs(os.path.join(self.d, "modell"))
        open(os.path.join(self.d, "stimmen", "Anton.safetensors"), "wb").close()
        p = os.path.join(self.d, "ptts")
        with open(p, "w") as f:
            f.write(ERSATZ)
        os.chmod(p, os.stat(p).st_mode | stat.S_IEXEC)
        self.alt, stimmen.PHONON = stimmen.PHONON, self.d

    def tearDown(self):
        stimmen.PHONON = self.alt

    def test_liste_und_sprechen(self):
        self.assertEqual([x["id"] for x in stimmen.ph_stimmen()], ["ph:Anton"])
        wav = stimmen.sprechen("Am Sa, 14.11. um 7:30 Uhr", "ph:Anton")
        self.assertTrue(wav.startswith(b"RIFF"))
        self.assertIn("Samstag, 14. November", open(os.path.join(self.d, "letzter-text")).read())  # wie bei Piper aufbereitet

    def test_unbekannte_stimme(self):
        with self.assertRaises(ValueError):
            stimmen.ph_sprechen("Hallo", "../../etc/passwd")
        with self.assertRaises(ValueError):
            stimmen.ph_sprechen("Hallo", "Henrik")  # nicht installiert


if __name__ == "__main__":
    unittest.main()
