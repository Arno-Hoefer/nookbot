#!/usr/bin/env bash
# NookBot 8 (optional): Sprachausgabe auf dem Pi mit Piper (lokal, frei) und deutschen Stimmen – für den Sprachmodus der App.
# Aufruf: sudo bash 8-stimmen.sh
set -euo pipefail
install -d -m 755 /opt/piper/stimmen
cd "$(mktemp -d)"
curl -fsSL -o piper.tgz https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_linux_aarch64.tar.gz
tar xzf piper.tgz && cp -r piper/* /opt/piper/
B=https://huggingface.co/rhasspy/piper-voices/resolve/main/de/de_DE
for v in thorsten/high thorsten/medium thorsten_emotional/medium kerstin/low ramona/low eva_k/x_low karlsson/low pavoque/low; do
  n="de_DE-$(echo "$v" | tr / -)"
  curl -fsSL -o "/opt/piper/stimmen/$n.onnx" "$B/$v/$n.onnx"
  curl -fsSL -o "/opt/piper/stimmen/$n.onnx.json" "$B/$v/$n.onnx.json"
done
chmod -R a+rX /opt/piper
# Eine geladene Stimme braucht ~300 MB – der Adapter darf deshalb mehr Speicher
install -d /etc/systemd/system/nookbot-adapter.service.d
printf '[Service]\nMemoryMax=900M\n' > /etc/systemd/system/nookbot-adapter.service.d/stimmen.conf
systemctl daemon-reload && systemctl restart nookbot-adapter
echo "Fertig – Stimmen: $(ls /opt/piper/stimmen/*.onnx | wc -l)"
