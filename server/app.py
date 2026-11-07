"""Run from the project root with python -m server.app."""
import json
import os
import re
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from .store import Problem, Rules, Store, actor_key, now_ms


class Handler(BaseHTTPRequestHandler):
    server_version = "RoadAlert/0.1"

    def log_message(self, *args):
        pass  # No request bodies, location history, bearer credentials or client IP logs.

    def handle_api(self):
        try:
            if self.path == "/health" and self.command == "GET":
                return self.reply(200, {"ready": True, "serverAt": now_ms()})
            actor = actor_key(self.headers.get("Authorization", ""))
            data = {}
            if self.command in ("POST", "PATCH"):
                size = self.headers.get("Content-Length", "")
                if not size.isdigit() or not 0 < int(size) <= 8192:
                    raise Problem(413, "Body length must be 1..8192 bytes")
                if self.headers.get_content_type() != "application/json":
                    raise Problem(415, "Use application/json")
                try:
                    data = json.loads(self.rfile.read(int(size)))
                except (ValueError, UnicodeError):
                    raise Problem(400, "Invalid JSON") from None
                if not isinstance(data, dict):
                    raise Problem(422, "Expected an object")
            store = self.server.store
            match = re.fullmatch(r"/v1/events/([a-f0-9-]{36})", self.path)
            if self.command == "POST" and self.path == "/v1/events":
                result = store.report(data, actor)
                return self.reply(200 if result["replayed"] else 201, result)
            if self.command == "POST" and self.path == "/v1/watch":
                return self.reply(200, store.watch(data, actor))
            if self.command == "GET" and match:
                return self.reply(200, store.detail(match[1], actor))
            if self.command == "PATCH" and match:
                return self.reply(200, store.finish(match[1], data.get("state"), actor))
            raise Problem(404, "Unknown resource")
        except Problem as error:
            self.reply(error.status, {"error": str(error)})
        except (ConnectionError, TimeoutError):
            pass

    def reply(self, code, body):
        payload = json.dumps(body, separators=(",", ":")).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    do_GET = do_POST = do_PATCH = handle_api


class LocalServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address, store):
        super().__init__(address, Handler)
        self.store = store

    def get_request(self):
        conn, addr = super().get_request()
        conn.settimeout(18)
        return conn, addr


def setting(name, default, low, high):
    value = int(os.environ.get(name, default))
    if not low <= value <= high:
        raise SystemExit(f"{name} must be between {low} and {high}")
    return value


def main():
    port = setting("ROAD_PORT", 8787, 1, 65535)
    rules = Rules(ttl_ms=1000 * setting("ROAD_TTL_SECONDS", 300, 2, 3600),
                  fix_age_ms=1000 * setting("ROAD_FIX_SECONDS", 20, 2, 60),
                  report_age_ms=1000 * setting("ROAD_REPORT_SECONDS", 90, 5, 600),
                  merge_time_ms=1000 * setting("ROAD_MERGE_SECONDS", 120, 1, 600),
                  merge_m=setting("ROAD_MERGE_METERS", 80, 5, 200),
                  ahead_m=setting("ROAD_AHEAD_METERS", 1500, 50, 4000),
                  retention_ms=1000 * setting("ROAD_RETAIN_SECONDS", 3600, 1, 86400))
    db_path = Path(os.environ.get("ROAD_DB", "road-alert.sqlite3"))
    db_path.parent.mkdir(parents=True, exist_ok=True)
    store = Store(db_path, rules)
    stop = threading.Event()

    def maintenance():
        while not stop.wait(1):
            store.maintain()

    with LocalServer(("127.0.0.1", port), store) as server:
        worker = threading.Thread(target=maintenance, daemon=True)
        worker.start()
        print(f"Pleos Road Alert on http://127.0.0.1:{port} (Ctrl+C to stop)", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass
        finally:
            stop.set()
            worker.join()


if __name__ == "__main__":
    main()
