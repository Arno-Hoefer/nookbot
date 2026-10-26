#!/usr/bin/env bash
# NookBot 11 (optional): Phonon-Stimmen von Gradium auf dem Pi – deutlich natürlicher als Piper, ohne Cloud und ohne Kontingent.
# Lizenz der deutschen 40M-Modelle: CC BY-NC 4.0 – nur für private, nicht-kommerzielle Nutzung.
# Vorher auf https://huggingface.co/gradium/phonon-40m-de einmal den Zugang anfordern (Freigabe kommt sofort)
# und unter Settings → Access Tokens ein Read-Token anlegen.
# Aufruf: sudo bash 11-phonon.sh   – das Token wird verdeckt abgefragt und nicht gespeichert.
set -euo pipefail
VER=0.4.2
ARCH=$(uname -m)
[ "$ARCH" = aarch64 ] || [ "$ARCH" = x86_64 ] || { echo "Nicht unterstützt: $ARCH"; exit 1; }
read -rsp "Hugging-Face-Token (Read): " HF; echo
[ -n "$HF" ] || { echo "Kein Token."; exit 1; }
D=/opt/phonon
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT

# Programm (Release von gradium-ai/xn-ptts, Prüfsumme wird geprüft)
U=https://github.com/gradium-ai/xn-ptts/releases/download/v$VER
A=ptts-$VER-$ARCH-unknown-linux-gnu.tar.gz
(cd "$T" && curl -fsSLO "$U/$A" && curl -fsSLO "$U/SHA256SUMS" && grep " $A\$" SHA256SUMS | sha256sum -c - && tar xzf "$A")
install -d -m 755 $D $D/modell $D/stimmen
install -m 755 "$T/ptts-$VER-$ARCH-unknown-linux-gnu/ptts" $D/ptts

# Modell (deutsch) und Stimmen-Katalog – nur die deutschen Stimmen
hf() { curl -fsSL -H "Authorization: Bearer $HF" "https://huggingface.co/gradium/$1/resolve/main/$2" -o "$3"; }
for f in config.json model.safetensors tokenizer.json; do hf phonon-40m-de $f $D/modell/$f; done
hf phonon-voices voices.jsonl "$T/voices.jsonl"
python3 - "$T/voices.jsonl" > "$T/de.txt" <<'P'
import json, sys
for z in open(sys.argv[1]):
    v = json.loads(z)
    if v.get("language") == "de":
        print(v["name"], v["filename"])
P
while read -r NAME DATEI; do
  hf phonon-voices "$DATEI" "$D/stimmen/$NAME.safetensors" && echo "Stimme $NAME"
done < "$T/de.txt"
chmod -R a+rX $D

# Probe: einmal ohne Netz sprechen (so ruft der Adapter es auf)
env -i PATH=/usr/bin:/bin HF_HUB_OFFLINE=1 $D/ptts -m $D/modell --quant q8_0 --lang de -v $D/stimmen/Anton.safetensors \
  -o "$T/probe.wav" "Hallo, hier spricht Phonon." >/dev/null 2>&1 && echo "Probe ok."
systemctl restart nookbot-adapter
echo "Fertig – die Phonon-Stimmen stehen jetzt in der App unter Bot → Stimme („… (Phonon)“)."
