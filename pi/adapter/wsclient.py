"""
Kleiner WebSocket-Client (RFC 6455) nur mit der Standardbibliothek – für die Verbindung Adapter → PicoClaw auf 127.0.0.1.
Kann: Handshake mit Header, Text-Frames senden (maskiert) und empfangen (auch fragmentiert), Ping/Pong, Close.
"""
import base64
import hashlib
import json
import os
import socket
import struct
import threading
import time
import urllib.parse

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


class WSError(Exception):
    pass


class WSClient:
    def __init__(self, url: str, headers: dict | None = None, on_text=None, on_close=None, ping_s: int = 20):
        self.url, self.headers = url, headers or {}
        self.on_text, self.on_close = on_text, on_close
        self.ping_s = ping_s
        self.sock: socket.socket | None = None
        self._wlock = threading.Lock()
        self.closed = threading.Event()

    def connect(self, timeout: float = 10) -> None:
        u = urllib.parse.urlparse(self.url)
        if u.scheme != "ws":
            raise WSError("nur ws:// (lokal)")
        s = socket.create_connection((u.hostname, u.port or 80), timeout=timeout)
        key = base64.b64encode(os.urandom(16)).decode()
        path = (u.path or "/") + (("?" + u.query) if u.query else "")
        lines = [f"GET {path} HTTP/1.1", f"Host: {u.hostname}:{u.port or 80}", "Upgrade: websocket", "Connection: Upgrade",
                 f"Sec-WebSocket-Key: {key}", "Sec-WebSocket-Version: 13"] + [f"{k}: {v}" for k, v in self.headers.items()]
        s.sendall(("\r\n".join(lines) + "\r\n\r\n").encode())
        resp = b""
        while b"\r\n\r\n" not in resp:
            chunk = s.recv(4096)
            if not chunk:
                raise WSError("Verbindung beim Handshake geschlossen")
            resp += chunk
        head, rest = resp.split(b"\r\n\r\n", 1)
        status = head.split(b"\r\n", 1)[0].decode(errors="replace")
        if " 101 " not in status + " ":
            raise WSError(status)
        want = base64.b64encode(hashlib.sha1((key + GUID).encode()).digest()).decode()
        if want.encode() not in head:
            raise WSError("falscher Sec-WebSocket-Accept")
        s.settimeout(None)
        self.sock = s
        self._buf = rest
        threading.Thread(target=self._read_loop, daemon=True, name="ws-read").start()
        threading.Thread(target=self._ping_loop, daemon=True, name="ws-ping").start()

    # ── Senden ──
    def _send_frame(self, opcode: int, data: bytes) -> None:
        if not self.sock or self.closed.is_set():
            raise WSError("nicht verbunden")
        n = len(data)
        head = bytes([0x80 | opcode])
        if n < 126:
            head += bytes([0x80 | n])
        elif n < 65536:
            head += bytes([0x80 | 126]) + struct.pack(">H", n)
        else:
            head += bytes([0x80 | 127]) + struct.pack(">Q", n)
        mask = os.urandom(4)
        body = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
        with self._wlock:
            self.sock.sendall(head + mask + body)

    def send_json(self, obj) -> None:
        self._send_frame(0x1, json.dumps(obj, ensure_ascii=False).encode())

    def close(self) -> None:
        if self.closed.is_set():
            return
        try:
            self._send_frame(0x8, struct.pack(">H", 1000))
        except Exception:  # noqa: BLE001
            pass
        self._shutdown()

    # ── Empfangen ──
    def _recv(self, n: int) -> bytes:
        while len(self._buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise WSError("Verbindung geschlossen")
            self._buf += chunk
        out, self._buf = self._buf[:n], self._buf[n:]
        return out

    def _read_frame(self):
        b1, b2 = self._recv(2)
        fin, op = b1 & 0x80, b1 & 0x0F
        n = b2 & 0x7F
        if n == 126:
            n = struct.unpack(">H", self._recv(2))[0]
        elif n == 127:
            n = struct.unpack(">Q", self._recv(8))[0]
        mask = self._recv(4) if b2 & 0x80 else None
        data = self._recv(n)
        if mask:
            data = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
        return bool(fin), op, data

    def _read_loop(self) -> None:
        parts: list[bytes] = []
        try:
            while not self.closed.is_set():
                fin, op, data = self._read_frame()
                if op == 0x9:
                    self._send_frame(0xA, data)
                elif op == 0xA:
                    pass
                elif op == 0x8:
                    break
                elif op in (0x1, 0x0, 0x2):
                    parts.append(data)
                    if fin:
                        msg, parts = b"".join(parts), []
                        if self.on_text:
                            try:
                                self.on_text(msg.decode("utf-8", "replace"))
                            except Exception as e:  # noqa: BLE001
                                print(f"ws on_text Fehler: {e}", flush=True)
        except Exception:  # noqa: BLE001
            pass
        self._shutdown()

    def _ping_loop(self) -> None:
        while not self.closed.wait(self.ping_s):
            try:
                self._send_frame(0x9, str(int(time.time())).encode())
            except Exception:  # noqa: BLE001
                self._shutdown()
                return

    def _shutdown(self) -> None:
        if self.closed.is_set():
            return
        self.closed.set()
        try:
            self.sock.close()
        except Exception:  # noqa: BLE001
            pass
        if self.on_close:
            try:
                self.on_close()
            except Exception:  # noqa: BLE001
                pass
