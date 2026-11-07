"""Incident storage and approach matching. Times are integer Unix milliseconds."""
import hashlib
import json
import re
import sqlite3
import threading
import time
import uuid
from dataclasses import dataclass


def now_ms():
    return time.time_ns() // 1_000_000


class Problem(Exception):
    def __init__(self, status, message):
        self.status = status
        super().__init__(message)


@dataclass(frozen=True)
class Rules:
    ttl_ms: int = 300_000
    fix_age_ms: int = 20_000
    report_age_ms: int = 90_000
    merge_time_ms: int = 120_000
    merge_m: int = 80
    ahead_m: int = 1500
    retention_ms: int = 3_600_000
    skew_ms: int = 5000


def integer(obj, key, low, high):
    value = obj.get(key)
    if type(value) is not int or not low <= value <= high:
        raise Problem(422, f"Invalid {key}")
    return value


def choice(obj, key, choices):
    value = obj.get(key)
    if not isinstance(value, str) or value not in choices:
        raise Problem(422, f"Invalid {key}")
    return value


def actor_key(header):
    if not re.fullmatch(r"Bearer [0-9a-f]{64}", header):
        raise Problem(401, "Participant credential required")
    return hashlib.sha256(header[7:].encode()).hexdigest()


def fix_fields(obj):
    if not isinstance(obj, dict):
        raise Problem(422, "Missing road fix")
    # Only this surveyed-by-construction test network is installed in this build.
    space = choice(obj, "space", {"demo"})
    road = choice(obj, "road", {"riverside", "crossway"})
    level = integer(obj, "level", -1, 1)
    direction = integer(obj, "direction", -1, 1)
    if direction == 0:
        raise Problem(422, "Unknown direction")
    return dict(space=space, road=road, level=level, direction=direction,
                offsetM=integer(obj, "offsetM", 0, 4000),
                accuracyM=integer(obj, "accuracyM", 1, 10000),
                observedAt=integer(obj, "observedAt", 1, 2**53))


def relevant(event, fix, now, rules):
    if event["state"] != "active" or now >= event["expiresAt"]:
        return False
    if not -rules.skew_ms <= now - fix["observedAt"] <= rules.fix_age_ms:
        return False
    if fix["accuracyM"] > 35:
        return False
    location = event["fix"]
    if any(fix[key] != location[key] for key in ("space", "road", "direction", "level")):
        return False
    gap = (location["offsetM"] - fix["offsetM"]) * fix["direction"]
    return fix["accuracyM"] + location["accuracyM"] < gap <= rules.ahead_m


class Store:
    def __init__(self, path, rules=Rules(), clock=now_ms):
        self.rules, self.clock = rules, clock
        self.changed = threading.Condition(threading.RLock())
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.executescript("""
            PRAGMA foreign_keys=ON;
            PRAGMA secure_delete=ON;
            CREATE TABLE IF NOT EXISTS event (
                id TEXT PRIMARY KEY, owner TEXT NOT NULL, body TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS receipt (
                actor TEXT NOT NULL, request_id TEXT NOT NULL, digest TEXT NOT NULL,
                event_id TEXT NOT NULL REFERENCES event(id) ON DELETE CASCADE,
                PRIMARY KEY(actor, request_id)
            );
        """)

    def close(self):
        with self.changed:
            self.db.close()

    def _write(self, event):
        self.db.execute("UPDATE event SET body=? WHERE id=?", (json.dumps(event), event["id"]))

    def _all(self):
        return [json.loads(row[0]) for row in self.db.execute("SELECT body FROM event")]

    def _maintain(self, now):
        for event in self._all():
            if event["state"] == "active" and now >= event["expiresAt"]:
                event.update(state="expired", updatedAt=event["expiresAt"])
                self._write(event)
            replay_window_passed = now - event["updatedAt"] > self.rules.report_age_ms + self.rules.skew_ms
            if event["state"] != "active" and now - event["updatedAt"] >= self.rules.retention_ms and replay_window_passed:
                self.db.execute("DELETE FROM event WHERE id=?", (event["id"],))

    def maintain(self):
        with self.changed, self.db:
            self._maintain(self.clock())

    def _load(self, event_id):
        row = self.db.execute("SELECT * FROM event WHERE id=?", (event_id,)).fetchone()
        if not row:
            raise Problem(404, "Incident unavailable")
        return json.loads(row["body"]), row["owner"]

    def detail(self, event_id, actor):
        with self.changed, self.db:
            self._maintain(self.clock())
            event, owner = self._load(event_id)
            return dict(event=event, canManage=actor == owner, serverAt=self.clock())

    def report(self, data, actor):
        with self.changed, self.db:
            now = self.clock()
            self._maintain(now)
            try:
                request_id = str(uuid.UUID(str(data.get("requestId"))))
            except ValueError:
                raise Problem(422, "Invalid requestId") from None
            kind = choice(data, "kind", {"crash", "stop", "hazard"})
            source = choice(data, "source", {"driver", "vehicle"})
            channel = choice(data, "input", {"manual", "simulator", "pleos", "aaos"})
            signal = choice(data, "signal", {"manual", "deployment", "hazards", "deceleration", "brake"})
            confirmed = data.get("confirmed")
            if type(confirmed) is not bool:
                raise Problem(422, "Invalid confirmation")
            if source == "driver":
                if (channel, signal, confirmed) != ("manual", "manual", True):
                    raise Problem(422, "Invalid driver report")
            elif channel == "manual" or signal == "manual":
                raise Problem(422, "Missing signal provenance")
            elif signal == "deployment":
                if kind != "crash" or confirmed or channel not in {"pleos", "simulator"}:
                    raise Problem(422, "Invalid deployment evidence")
            elif not confirmed or kind != "hazard":
                raise Problem(422, "A weak signal requires human confirmation of a hazard")
            fix = fix_fields(data.get("fix"))
            occurred = integer(data, "happenedAt", 1, 2**53)
            created = integer(data, "createdAt", 1, 2**53)
            sent = integer(data, "sentAt", 1, 2**53)
            identity = [kind, source, channel, signal, confirmed, fix, occurred, created]
            digest = hashlib.sha256(json.dumps(identity, sort_keys=True).encode()).hexdigest()
            previous = self.db.execute(
                "SELECT * FROM receipt WHERE actor=? AND request_id=?", (actor, request_id)
            ).fetchone()
            if previous:
                if previous["digest"] != digest:
                    raise Problem(409, "requestId reused with different content")
                return self.detail(previous["event_id"], actor) | {"replayed": True}
            if not -self.rules.skew_ms <= now - occurred <= self.rules.report_age_ms:
                raise Problem(422, "Report too old or clock skew")
            if not occurred <= created <= sent <= now + self.rules.skew_ms:
                raise Problem(422, "Invalid trace timeline")
            if fix["accuracyM"] > 35 or not -self.rules.skew_ms <= occurred - fix["observedAt"] <= self.rules.fix_age_ms:
                raise Problem(422, "Incident needs an accurate contemporaneous fix")
            matches = [e for e in self._all() if e["state"] == "active" and e["kind"] == kind
                       and abs(e["happenedAt"] - occurred) <= self.rules.merge_time_ms
                       and all(e["fix"][k] == fix[k] for k in ("space", "road", "direction", "level"))
                       and abs(e["fix"]["offsetM"] - fix["offsetM"]) <= self.rules.merge_m]
            if matches:
                event = min(matches, key=lambda e: (abs(e["fix"]["offsetM"] - fix["offsetM"]), e["id"]))
                known = self.db.execute("SELECT 1 FROM receipt WHERE event_id=? AND actor=?",
                                        (event["id"], actor)).fetchone()
                if not known:
                    event["participants"] += 1
                    event.update(verification="corroborated", expiresAt=now + self.rules.ttl_ms)
                elif confirmed and event["verification"] == "unverified":
                    event["verification"] = "reporter"
                event["updatedAt"] = now
                self._write(event)
            else:
                event = dict(id=str(uuid.uuid4()), kind=kind, source=source, input=channel,
                             signal=signal, demo=fix["space"] == "demo" or channel == "simulator",
                             verification="reporter" if confirmed else "unverified", state="active",
                             participants=1, fix=fix, happenedAt=occurred, createdAt=created,
                             sentAt=sent, receivedAt=now, updatedAt=now, expiresAt=now + self.rules.ttl_ms)
                self.db.execute("INSERT INTO event VALUES(?,?,?)", (event["id"], actor, json.dumps(event)))
            self.db.execute("INSERT INTO receipt VALUES(?,?,?,?)", (actor, request_id, digest, event["id"]))
            self.changed.notify_all()
            _, owner = self._load(event["id"])
            return dict(event=event, canManage=actor == owner, replayed=False, serverAt=now)

    def finish(self, event_id, state, actor):
        if state not in ("resolved", "cancelled"):
            raise Problem(422, "Unknown final state")
        with self.changed, self.db:
            now = self.clock()
            self._maintain(now)
            event, owner = self._load(event_id)
            if owner != actor:
                raise Problem(403, "Only the original reporter may close the incident")
            if event["state"] != state:
                if event["state"] != "active":
                    raise Problem(409, "Incident already closed")
                event.update(state=state, updatedAt=now)
                self._write(event)
                self.changed.notify_all()
            return dict(event=event, canManage=True, serverAt=now)

    def watch(self, data, actor):
        fix = fix_fields(data.get("fix"))
        wait = integer(data, "waitMs", 0, 12000)
        cursor = data.get("cursor", "")
        if not isinstance(cursor, str) or len(cursor) > 64:
            raise Problem(422, "Invalid cursor")
        until = time.monotonic() + wait / 1000
        with self.changed:
            while True:
                now = self.clock()
                with self.db:
                    self._maintain(now)
                    rows = self.db.execute("SELECT body FROM event WHERE owner<>?", (actor,))
                    matches = [e for (body,) in rows if relevant(e := json.loads(body), fix, now, self.rules)]
                matches.sort(key=lambda e: (abs(e["fix"]["offsetM"] - fix["offsetM"]), e["id"]))
                fresh = -self.rules.skew_ms <= now - fix["observedAt"] <= self.rules.fix_age_ms and fix["accuracyM"] <= 35
                matches = matches[:3]
                snapshot = [fresh, [[e["id"], e["updatedAt"], e["verification"], e["expiresAt"]] for e in matches]]
                revision = hashlib.sha256(json.dumps(snapshot).encode()).hexdigest()
                remaining = until - time.monotonic()
                if revision != cursor or remaining <= 0 or not fresh:
                    return dict(events=matches, cursor=revision, fresh=fresh, serverAt=now)
                deadlines = [fix["observedAt"] + self.rules.fix_age_ms + 1] + [e["expiresAt"] for e in matches]
                self.changed.wait(min(remaining, max(0.001, (min(deadlines) - now) / 1000)))
