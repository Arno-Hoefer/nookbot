"""Ereignisse für die App (Server-Sent Events)."""
import threading


# ── Ereignisse für die App (SSE) ───────────────────────────────────

class Events:
    def __init__(self):
        self.seq = 0
        self.buf: list[tuple[int, dict]] = []
        self.cond = threading.Condition()
        self.clients = 0

    def emit(self, ev: dict):
        with self.cond:
            self.seq += 1
            self.buf.append((self.seq, ev))
            del self.buf[:-3000]
            self.cond.notify_all()

    def since(self, n: int):
        with self.cond:
            if self.buf and n < self.buf[0][0] - 1:
                return None  # zu alt → App lädt neu
            return [(s, e) for s, e in self.buf if s > n]


EV = Events()
