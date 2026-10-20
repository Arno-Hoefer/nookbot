#!/usr/bin/env bash
# NookBot 3/6: Kapitän „Reacher“ einrichten und PicoClaw als abgeschotteten Dienst starten.
# Aufruf: sudo bash 3-picoclaw-dienst.sh   (erwartet /tmp/kapitaen/{AGENT,SOUL,USER}.md – USER.md aus USER.example.md anlegen)
set -uo pipefail
W=/srv/picoclaw/.picoclaw/workspace

echo "== Kapitän Reacher"
install -o picoclaw -g picoclaw -m 644 /tmp/kapitaen/AGENT.md /tmp/kapitaen/SOUL.md /tmp/kapitaen/USER.md "$W/"
# Skills, die Shell/Browser/Hardware bräuchten (alles aus) oder neue Skills schreiben: beiseitelegen, nicht löschen
install -d -o picoclaw -g picoclaw -m 700 /srv/picoclaw/skills-aus
for s in agent-browser github hardware tmux skill-creator picoclaw-agent; do
  [ -d "$W/skills/$s" ] && mv "$W/skills/$s" /srv/picoclaw/skills-aus/
done
ls "$W/skills"

echo "== Dienst picoclaw-gateway"
cat > /etc/systemd/system/picoclaw-gateway.service <<'UNIT'
[Unit]
Description=NookBot – PicoClaw Gateway (Kapitän Reacher, nur lokal)
After=network-online.target home-mcp.service
Wants=network-online.target

[Service]
User=picoclaw
Group=picoclaw
WorkingDirectory=/srv/picoclaw
Environment=HOME=/srv/picoclaw
ExecStart=/opt/picoclaw/picoclaw --no-color gateway
Restart=always
RestartSec=5

NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
ReadWritePaths=/srv/picoclaw
InaccessiblePaths=-/srv/home-mcp -/srv/echo-bot -/opt/home-mcp -/root -/srv/nanobot -/srv/openclaw
PrivateTmp=yes
PrivateDevices=yes
ProtectKernelTunables=yes
ProtectKernelModules=yes
ProtectControlGroups=yes
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX AF_NETLINK
MemoryAccounting=yes
MemoryHigh=200M
MemoryMax=400M

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable --now picoclaw-gateway.service
sleep 5
systemctl is-active picoclaw-gateway.service
curl -fsS http://127.0.0.1:18790/health; echo
journalctl -u picoclaw-gateway -n 8 --no-pager -o cat | cut -c1-160
m=$(systemctl show -p MemoryCurrent --value picoclaw-gateway); echo "picoclaw RAM $(( m / 1048576 )) MB"
echo FERTIG
