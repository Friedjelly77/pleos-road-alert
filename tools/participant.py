"""Second road participant. All coordinates are positions on the synthetic road."""
import argparse
import json
import os
import secrets
import time
import urllib.request
import uuid


def milliseconds():
    return time.time_ns() // 1_000_000


def credentials():
    return secrets.token_hex(32)


def call(base, key, path, data=None, method=None):
    payload = json.dumps(data).encode() if data is not None else None
    req = urllib.request.Request(base + path, data=payload, method=method,
                                 headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=18) as response:
        return json.load(response)


def point(offset=1000, direction=1, road="riverside", level=0, stamp=None):
    return dict(road=road, level=level, offsetM=offset, direction=direction, space="demo",
                accuracyM=5, observedAt=milliseconds() if stamp is None else stamp)


def report(fix, kind="crash", stamp=None):
    at = milliseconds() if stamp is None else stamp
    return dict(requestId=str(uuid.uuid4()), kind=kind, source="driver", input="manual",
                signal="manual", confirmed=True, happenedAt=at, createdAt=at, sentAt=at, fix=fix)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("watch", "report"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("ROAD_PORT", 8787)))
    parser.add_argument("--offset", type=int)
    parser.add_argument("--direction", type=int, choices=(-1, 1), default=1)
    parser.add_argument("--road", choices=("riverside", "crossway"), default="riverside")
    parser.add_argument("--level", type=int, choices=(-1, 0, 1), default=0)
    parser.add_argument("--kind", choices=("crash", "stop", "hazard"), default="crash")
    args = parser.parse_args()
    base = f"http://127.0.0.1:{args.port}"
    key = credentials()
    offset = args.offset if args.offset is not None else (1000 if args.action == "report" else 400)

    def fix():
        return point(offset, args.direction, args.road, args.level)

    if args.action == "report":
        reply = call(base, key, "/v1/events", report(fix(), args.kind))
        event_id = reply["event"]["id"]
        print(json.dumps(reply, indent=2), flush=True)
        try:
            action = input("resolve / cancel / Enter to leave active > ").strip()
        except EOFError:
            return
        if action in ("resolve", "cancel"):
            result = call(base, key, "/v1/events/" + event_id,
                          {"state": {"resolve": "resolved", "cancel": "cancelled"}[action]}, "PATCH")
            print(result["event"]["state"])
    else:
        cursor = ""
        while True:
            reply = call(base, key, "/v1/watch", dict(fix=fix(), waitMs=8000, cursor=cursor))
            received = milliseconds()
            if reply["cursor"] != cursor:
                print(json.dumps(dict(clientReceivedAt=received, **reply)), flush=True)
            cursor = reply["cursor"]


if __name__ == "__main__":
    main()
