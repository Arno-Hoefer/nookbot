#!/usr/bin/env bash
# NookBot 1/6: PicoClaw (Go, eine Datei) als eigener Benutzer picoclaw installieren, Prüfsumme wird geprüft.
# Aufruf: sudo bash 1-picoclaw-installieren.sh
set -uo pipefail
PICO_VER=v0.3.1
case "$(uname -m)" in aarch64) PAKET=picoclaw_Linux_arm64.tar.gz ;; x86_64) PAKET=picoclaw_Linux_x86_64.tar.gz ;;
  *) echo "Nicht unterstützt: $(uname -m)"; exit 1 ;; esac

echo "== Benutzer"
for u in picoclaw; do
  id $u >/dev/null 2>&1 || useradd --system --home-dir /srv/$u --create-home --shell /usr/sbin/nologin $u
  install -d -o $u -g $u -m 700 /srv/$u
done

echo "== PicoClaw $PICO_VER"
TMP=$(mktemp -d)
B=https://github.com/sipeed/picoclaw/releases/download/$PICO_VER
curl -fsSL "$B/$PAKET" -o "$TMP/$PAKET"
curl -fsSL "$B/picoclaw_${PICO_VER#v}_checksums.txt" -o "$TMP/sums.txt"
(cd "$TMP" && grep " $PAKET\$" sums.txt | sha256sum -c -) || { echo "PRUEFSUMME FALSCH"; exit 1; }
rm -rf /opt/picoclaw && install -d /opt/picoclaw
tar xzf "$TMP/$PAKET" -C /opt/picoclaw --no-same-owner   # Dateien gehören root, nicht der UID aus dem Archiv
rm -rf "$TMP"
ls -la /opt/picoclaw
sudo -u picoclaw env HOME=/srv/picoclaw /opt/picoclaw/picoclaw version 2>&1 | head -3

echo "== Konfiguration und Arbeitsbereich (picoclaw onboard) – nur beim ersten Mal"
# Legt /srv/picoclaw/.picoclaw/config.json und workspace/ an; 2-modell-und-websuche.sh baut darauf auf.
# Steht die Konfiguration schon da (z. B. aus einer Sicherung), bleibt sie unangetastet.
if [ -f /srv/picoclaw/.picoclaw/config.json ]; then
  echo "config.json vorhanden – übersprungen"
else
  (cd /srv/picoclaw && sudo -u picoclaw env HOME=/srv/picoclaw /opt/picoclaw/picoclaw --no-color onboard </dev/null)
  ls -la /srv/picoclaw/.picoclaw
fi

echo "== Aufrufhilfen"
cat > /usr/local/bin/pico <<'EOS'
#!/usr/bin/env bash
cd /srv/picoclaw && exec sudo -u picoclaw env HOME=/srv/picoclaw /opt/picoclaw/picoclaw "$@"
EOS
chmod 755 /usr/local/bin/pico

echo "== Platz"
du -sh /opt/picoclaw
free -m | head -2
echo FERTIG
