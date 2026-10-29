#!/usr/bin/env bash
# NookBot 10 (optional): Pi-Rechner – Python für die Bots (abgeschottet mit bubblewrap: kein Netz, nur der Arbeitsordner),
# Seitenbilder über den Pi-Browser. Aufruf: sudo bash 10-rechner.sh
set -euo pipefail
apt-get install -y -qq bubblewrap python3-pandas python3-matplotlib python3-openpyxl python3-numpy python3-pypdf python3-docx >/dev/null
# bubblewrap richtet im Sandkasten ein eigenes Loopback ein – dafür braucht der Adapter Netlink (nur lokal, kein Netz nach außen)
install -d /etc/systemd/system/nookbot-adapter.service.d
printf '[Service]\nRestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX AF_NETLINK\n' > /etc/systemd/system/nookbot-adapter.service.d/rechner.conf
systemctl daemon-reload && systemctl restart nookbot-adapter
echo "Fertig – Python-Sandkasten bereit. Arbeitsordner: /srv/picoclaw/nookbot/arbeit"
