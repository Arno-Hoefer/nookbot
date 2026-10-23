#!/usr/bin/env bash
# NookBot 6/6: Adapter einrichten: Programm nach /opt/nookbot, Dienst nookbot-adapter (Benutzer picoclaw, 127.0.0.1:8795),
# Vorlage picoclaw@.service für weitere Bots, Polkit-Regel (picoclaw darf NUR die Bot-Dienste starten/stoppen/neu starten),
# Tailnet :8444 → Adapter (statt direkt PicoClaw).
# Aufruf: sudo bash 6-adapter.sh   (erwartet /tmp/nookbot-adapter/{nookbot_server.py,wsclient.py,nookbot/,web/})
set -euo pipefail

echo "== Programm"
install -d -m 755 /opt/nookbot
install -m 644 /tmp/nookbot-adapter/nookbot_server.py /tmp/nookbot-adapter/wsclient.py /opt/nookbot/
# Der Code liegt im Paket nookbot/ – immer das ganze Verzeichnis ersetzen (alte Module dürfen nicht liegen bleiben)
rm -rf /opt/nookbot/nookbot
install -d -m 755 /opt/nookbot/nookbot
install -m 644 /tmp/nookbot-adapter/nookbot/*.py /opt/nookbot/nookbot/
install -d -m 755 /opt/nookbot/web
install -m 644 /tmp/nookbot-adapter/web/* /opt/nookbot/web/
install -d -o picoclaw -g picoclaw -m 700 /srv/picoclaw/nookbot /srv/picoclaw/bots

echo "== Vorlage für weitere Bots: picoclaw@<id>.service"
cat > /etc/systemd/system/picoclaw@.service <<'UNIT'
[Unit]
Description=NookBot – Bot %i (PicoClaw, nur lokal)
After=network-online.target home-mcp.service
Wants=network-online.target

[Service]
User=picoclaw
Group=picoclaw
WorkingDirectory=/srv/picoclaw/bots/%i
Environment=HOME=/srv/picoclaw/bots/%i
ExecStart=/opt/picoclaw/picoclaw --no-color gateway
Restart=always
RestartSec=5
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
ReadWritePaths=/srv/picoclaw/bots/%i
InaccessiblePaths=-/srv/home-mcp -/srv/echo-bot -/opt/home-mcp -/root
PrivateTmp=yes
PrivateDevices=yes
ProtectKernelTunables=yes
ProtectKernelModules=yes
ProtectControlGroups=yes
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX AF_NETLINK
MemoryAccounting=yes
MemoryHigh=150M
MemoryMax=300M
UNIT

echo "== Polkit: picoclaw darf nur Bot-Dienste starten/stoppen/neu starten"
cat > /etc/polkit-1/rules.d/50-nookbot.rules <<'RULES'
// NookBot-Adapter (Benutzer picoclaw) verwaltet die Bot-Prozesse – sonst nichts.
polkit.addRule(function(action, subject) {
    if (action.id == "org.freedesktop.systemd1.manage-units" && subject.user == "picoclaw") {
        var unit = action.lookup("unit");
        var verb = action.lookup("verb");
        if (unit && /^(picoclaw-gateway|picoclaw@[a-z0-9-]+)\.service$/.test(unit) &&
            (verb == "start" || verb == "stop" || verb == "restart")) {
            return polkit.Result.YES;
        }
    }
});
RULES
systemctl restart polkit 2>/dev/null || true

echo "== Dienst nookbot-adapter"
cat > /etc/systemd/system/nookbot-adapter.service <<'UNIT'
[Unit]
Description=NookBot-Adapter (App ↔ Bots, Verlauf, Routinen, Runden) – 127.0.0.1:8795
After=network-online.target picoclaw-gateway.service
Wants=network-online.target

[Service]
User=picoclaw
Group=picoclaw
WorkingDirectory=/srv/picoclaw
Environment=HOME=/srv/picoclaw
Environment=PYTHONUNBUFFERED=1
ExecStart=/usr/bin/python3 /opt/nookbot/nookbot_server.py
Restart=always
RestartSec=3
ProtectSystem=strict
ProtectHome=yes
ReadWritePaths=/srv/picoclaw
InaccessiblePaths=-/srv/home-mcp -/srv/echo-bot -/opt/home-mcp -/root
PrivateTmp=yes
PrivateDevices=yes
ProtectKernelTunables=yes
ProtectKernelModules=yes
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
MemoryAccounting=yes
MemoryMax=900M

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable nookbot-adapter.service >/dev/null
systemctl restart nookbot-adapter.service
for i in $(seq 1 20); do curl -fsS http://127.0.0.1:8795/ping 2>/dev/null && break; sleep 1; done; echo
systemctl is-active nookbot-adapter

echo "== Tailnet :8444 → Adapter (nur Tailnet, kein Funnel)"
tailscale serve --bg --https=8444 http://127.0.0.1:8795 >/dev/null
tailscale serve status 2>&1 | grep -A2 ":8444"
journalctl -u nookbot-adapter -n 10 --no-pager -o cat
echo FERTIG
