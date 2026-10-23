"""Start: Bots synchronisieren, Server, Hintergrund-Threads."""
import os
import sys
import threading
import time
from http.server import ThreadingHTTPServer

from . import basis, claude, bots, sitzungen, automatik, schnittstelle


def start_bots():
    """Beim Start: alle Bots synchronisieren und ihre Dienste starten (neue Bots sind nicht „enabled“)."""
    for b in bots.bots():
        try:
            if not bots.unit_active(b["unit"]):
                bots.sync_bot(b, restart=False)
                bots.unit("start", b["unit"])
            else:
                bots.sync_bot(b)
        except Exception as e:  # noqa: BLE001
            basis.log("Bot", b["id"], "Start:", e)
def main():
    os.makedirs(basis.path("sessions"), exist_ok=True)
    os.makedirs(basis.path("ausloeser"), exist_ok=True)
    basis.conf()

    class Server(ThreadingHTTPServer):
        def handle_error(self, request, client_address):  # abgebrochene App-Verbindungen sind normal
            if not isinstance(sys.exc_info()[1], (ConnectionResetError, BrokenPipeError, TimeoutError)):
                super().handle_error(request, client_address)

    # Erst lauschen, dann Bots (neu) starten – PicoClaw verbindet sich beim Start mit /mcp/<bot> und versucht es nicht erneut.
    srv = Server(("127.0.0.1", basis.PORT), schnittstelle.Handler)
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, daemon=True, name="http").start()
    start_bots()
    try:
        claude.claude_schluessel_verteilen()
    except Exception as e:  # noqa: BLE001
        basis.log("Claude-Schlüssel:", e)
    for fn in (sitzungen.watchdog, automatik.scheduler, automatik.waechter):
        threading.Thread(target=fn, daemon=True, name=fn.__name__).start()
    basis.log(f"nookbot {basis.VERSION} auf 127.0.0.1:{basis.PORT} – Bots: {', '.join(b['name'] for b in bots.bots())}")
    while True:
        time.sleep(3600)
