#!/usr/bin/env bash
# NookBot 9 (optional): Spracherkennung auf dem Pi (whisper.cpp) – fürs Diktieren in der Web-Oberfläche.
# Die App erkennt Sprache weiter auf dem Handy. Aufruf: sudo bash 9-whisper.sh   (Bauen dauert auf dem Pi 5 ~5 min)
set -euo pipefail
apt-get install -y -qq cmake g++ git ffmpeg >/dev/null
cd "$(mktemp -d)"
git clone -q --depth 1 --branch v1.9.4 https://github.com/ggml-org/whisper.cpp.git
cmake -S whisper.cpp -B build -DCMAKE_BUILD_TYPE=Release -DWHISPER_BUILD_TESTS=OFF -DBUILD_SHARED_LIBS=OFF >/dev/null
cmake --build build -j3 --target whisper-cli >/dev/null
install -d -m 755 /opt/nookbot/whisper
install -m 755 build/bin/whisper-cli /opt/nookbot/whisper/whisper-cli
M=/opt/nookbot/whisper/ggml-small-q5_1.bin
[ -s "$M" ] || curl -fsSL -o "$M" https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin
[ "$(stat -c %s "$M")" = 190085487 ] || { echo "Modell unvollständig"; rm -f "$M"; exit 1; }
chmod -R a+rX /opt/nookbot/whisper
# Stimme (~300 MB) und Spracherkennung (~500 MB) können gleichzeitig laufen
install -d /etc/systemd/system/nookbot-adapter.service.d
printf '[Service]\nMemoryMax=1500M\n' > /etc/systemd/system/nookbot-adapter.service.d/stimmen.conf
systemctl daemon-reload && systemctl restart nookbot-adapter
echo "Fertig – Spracherkennung in /opt/nookbot/whisper"
