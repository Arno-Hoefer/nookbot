#!/usr/bin/env bash
# NookBot 1/6: PicoClaw (Go, eine Datei) als eigener Benutzer picoclaw installieren, Prüfsumme wird geprüft.
# Aufruf: sudo bash 1-picoclaw-installieren.sh
set -uo pipefail
PICO_VER=v0.3.1

echo "== Benutzer"
for u in picoclaw; do
  id $u >/dev/null 2>&1 || useradd --system --home-dir /srv/$u --create-home --shell /usr/sbin/nologin $u
  install -d -o $u -g $u -m 700 /srv/$u
done

echo "== PicoClaw $PICO_VER"
TMP=$(mktemp -d)
B=https://github.com/sipeed/picoclaw/releases/download/$PICO_VER
curl -fsSL "$B/picoclaw_Linux_arm64.tar.gz" -o "$TMP/picoclaw_Linux_arm64.tar.gz"
curl -fsSL "$B/picoclaw_${PICO_VER#v}_checksums.txt" -o "$TMP/sums.txt"
(cd "$TMP" && grep " picoclaw_Linux_arm64.tar.gz\$" sums.txt | sha256sum -c -) || { echo "PRUEFSUMME FALSCH"; exit 1; }
rm -rf /opt/picoclaw && install -d /opt/picoclaw
tar xzf "$TMP/picoclaw_Linux_arm64.tar.gz" -C /opt/picoclaw
rm -rf "$TMP"
ls -la /opt/picoclaw
sudo -u picoclaw env HOME=/srv/picoclaw /opt/picoclaw/picoclaw version 2>&1 | head -3

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
