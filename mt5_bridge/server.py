import json
import os
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Lock

HOST = os.getenv("HOST", "0.0.0.0")
PORT = int(os.getenv("PORT", "8080"))
TOKEN = os.getenv("MT5_BRIDGE_TOKEN", "CHANGE_ME")
MAX_AGE_SECONDS = int(os.getenv("MAX_AGE_SECONDS", "10"))

_state = {"received_at": 0.0, "payload": None}
_lock = Lock()

class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body):
        data = json.dumps(body, separators=(",", ":")).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/health":
            with _lock:
                age = None if _state["received_at"] == 0 else time.time() - _state["received_at"]
            self._send(200, {"ok": True, "ageSeconds": age})
            return
        if self.path == "/xau/latest":
            with _lock:
                received = _state["received_at"]
                payload = _state["payload"]
            if payload is None or time.time() - received > MAX_AGE_SECONDS:
                self._send(503, {"ok": False, "message": "No fresh MT5 data"})
                return
            out = dict(payload)
            out["bridgeReceivedAt"] = int(received * 1000)
            out["bridgeAgeSeconds"] = round(time.time() - received, 3)
            self._send(200, out)
            return
        self._send(404, {"ok": False})

    def do_POST(self):
        if self.path != "/mt5/update":
            self._send(404, {"ok": False})
            return
        auth = self.headers.get("Authorization", "")
        expected = "Bearer " + TOKEN
        if TOKEN == "CHANGE_ME" or auth != expected:
            self._send(401, {"ok": False, "message": "Unauthorized"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            raw = self.rfile.read(length)
            payload = json.loads(raw.decode("utf-8"))
            if payload.get("symbol") != "XAUUSD":
                self._send(400, {"ok": False, "message": "Only XAUUSD is accepted"})
                return
            if not float(payload.get("bid", 0)) or not float(payload.get("ask", 0)):
                self._send(400, {"ok": False, "message": "Invalid quote"})
                return
            with _lock:
                _state["received_at"] = time.time()
                _state["payload"] = payload
            self._send(200, {"ok": True})
        except Exception as exc:
            self._send(400, {"ok": False, "message": str(exc)})

    def log_message(self, fmt, *args):
        return

if __name__ == "__main__":
    print(f"XAU MT5 bridge listening on {HOST}:{PORT}")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
