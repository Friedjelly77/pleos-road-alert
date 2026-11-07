import concurrent.futures
import json
import tempfile
import threading
import time
import unittest
import urllib.error
from pathlib import Path

from server.app import LocalServer
from server.store import Problem, Rules, Store
from tools.participant import call, credentials, milliseconds, point, report


class RoadRules(unittest.TestCase):
    def setUp(self):
        self.t = 1_800_000_000_000
        self.store = Store(":memory:", Rules(), lambda: self.t)
        self.addCleanup(self.store.close)
        self.a, self.b = credentials(), credentials()

    def add(self, key=None, offset=1000, **changes):
        data = report(point(offset, stamp=self.t), stamp=self.t) | changes
        return self.store.report(data, key or self.a)["event"]

    def watch(self, offset=400, direction=1, road="riverside", level=0, **changes):
        fix = point(offset, direction, road, level, self.t) | changes
        return self.store.watch({"fix": fix, "waitMs": 0}, self.b)["events"]

    def test_direction_and_approach(self):
        event = self.add()
        self.assertEqual([event["id"]], [e["id"] for e in self.watch()])
        for desc, args in [("opposite", (1400, -1)), ("passed", (1200,)), ("coincident", (1000,)),
                           ("uncertainty envelopes overlap", (994,)), ("intersection", (400, 1, "crossway")),
                           ("upper", (400, 1, "riverside", 1)), ("lower", (400, 1, "riverside", -1))]:
            with self.subTest(desc=desc):
                self.assertEqual([], self.watch(*args))

    def test_reverse_road_and_distance(self):
        self.add(fix=point(1500, -1, stamp=self.t))
        self.assertEqual(1, len(self.watch(2000, -1)))
        self.assertEqual([], self.watch(1000, -1))
        self.assertEqual([], self.watch(3100, -1))

    def test_freshness_accuracy_and_unknown_network(self):
        self.add()
        self.assertEqual([], self.watch(observedAt=self.t - 21000))
        self.assertEqual([], self.watch(observedAt=self.t + 6000))
        self.assertEqual([], self.watch(accuracyM=36))
        with self.assertRaises(Problem):
            self.watch(space="live")
        with self.assertRaises(Problem):
            self.add(fix=point(800, stamp=self.t - 60000))

    def test_merged_but_location_and_origin_immutable(self):
        first = self.add()
        self.t += 1700
        other = self.add(self.b, offset=1060)
        self.assertEqual(first["id"], other["id"])
        self.assertEqual((2, "corroborated"), (other["participants"], other["verification"]))
        self.assertEqual(first["fix"], other["fix"])
        self.assertEqual(first["happenedAt"], other["happenedAt"])
        self.assertGreater(other["expiresAt"], first["expiresAt"])
        self.assertNotEqual(first["id"], self.add(offset=1300)["id"])
        self.assertNotEqual(first["id"], self.add(fix=point(1000, -1, stamp=self.t))["id"])
        self.assertNotEqual(first["id"], self.add(kind="stop")["id"])
        self.assertNotEqual(first["id"], self.add(fix=point(1000, level=1, stamp=self.t))["id"])

    def test_weak_signals_and_simulated_deployment_remain_separate(self):
        for s in ("hazards", "brake", "deceleration"):
            with self.subTest(signal=s), self.assertRaises(Problem):
                self.add(source="vehicle", input="simulator", signal=s, confirmed=False)
        hazard = self.add(kind="hazard", source="vehicle", input="simulator", signal="hazards")
        self.assertEqual("reporter", hazard["verification"])
        deployed = self.add(source="vehicle", input="simulator", signal="deployment", confirmed=False)
        self.assertEqual(("unverified", True, "simulator", "vehicle"),
                         (deployed["verification"], deployed["demo"], deployed["input"], deployed["source"]))
        sdk = self.add(offset=1600, source="vehicle", input="pleos", signal="deployment", confirmed=False)
        self.assertEqual((True, "pleos"), (sdk["demo"], sdk["input"]))  # A test road remains a test road.

    def test_replay_conflict_and_owner_only_terminal_transitions(self):
        for state in ("resolved", "cancelled"):
            data = report(point(stamp=self.t), stamp=self.t)
            event = self.store.report(data, self.a)["event"]
            with self.assertRaises(Problem) as denied:
                self.store.finish(event["id"], state, self.b)
            self.assertEqual(403, denied.exception.status)
            self.store.finish(event["id"], state, self.a)
            self.store.finish(event["id"], state, self.a)
            self.assertEqual([], self.watch())
            replay = self.store.report(data | {"sentAt": self.t + 1}, self.a)
            self.assertTrue(replay["replayed"])
            self.assertEqual(state, replay["event"]["state"])
            with self.assertRaises(Problem) as changed:
                self.store.report(data | {"kind": "hazard"}, self.a)
            self.assertEqual(409, changed.exception.status)
            with self.assertRaises(Problem):
                self.store.finish(event["id"], "cancelled" if state == "resolved" else "resolved", self.a)

    def test_expiry_cannot_refresh_or_resurrect_and_receipts_purge(self):
        data = report(point(stamp=self.t), stamp=self.t)
        event = self.store.report(data, self.a)["event"]
        self.t = event["expiresAt"]
        self.assertEqual([], self.watch())
        self.assertEqual("expired", self.store.report(data, self.a)["event"]["state"])
        self.t += self.store.rules.retention_ms + 1
        self.store.maintain()
        self.assertEqual(0, self.store.db.execute("SELECT COUNT(*) FROM receipt").fetchone()[0])
        with self.assertRaises(Problem):
            self.store.report(data, self.a)

    def test_concurrent_identical_report_and_no_tracks(self):
        data = report(point(stamp=self.t), stamp=self.t)
        with concurrent.futures.ThreadPoolExecutor(8) as pool:
            replies = list(pool.map(lambda _: self.store.report(data, self.a), range(16)))
        self.assertEqual(1, len({r["event"]["id"] for r in replies}))
        self.assertEqual(1, sum(not r["replayed"] for r in replies))
        for offset in range(10, 900, 25):
            self.watch(offset)
        self.assertEqual(1, self.store.db.execute("SELECT COUNT(*) FROM event").fetchone()[0])
        self.assertEqual(1, self.store.db.execute("SELECT COUNT(*) FROM receipt").fetchone()[0])


class HttpParticipants(unittest.TestCase):
    def test_two_clients_long_poll_lost_ack_restart_and_resolution(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "events.db"
            a, b = credentials(), credentials()
            def launch(port=0):
                store = Store(path)
                server = LocalServer(("127.0.0.1", port), store)
                t = threading.Thread(target=server.serve_forever, daemon=True)
                t.start()
                return server, store, t
            def halt(server, store, thread):
                server.shutdown(); server.server_close(); thread.join(); store.close()
            srv, db, thread = launch()
            port = srv.server_port
            base = f"http://127.0.0.1:{port}"
            data = report(point())
            try:
                def watch(cursor="", wait=0):
                    return call(base, b, "/v1/watch", {"fix": point(400), "cursor": cursor, "waitMs": wait})
                empty = watch()
                with concurrent.futures.ThreadPoolExecutor(1) as pool:
                    waiting = pool.submit(watch, empty["cursor"], 3000)
                    time.sleep(.05)
                    result = call(base, a, "/v1/events", data)
                    alert = waiting.result(4)
                event = alert["events"][0]
                self.assertEqual(result["event"]["id"], event["id"])
                rx = milliseconds()
                print("HTTP_TRACE " + json.dumps({k: event[k] for k in ("createdAt", "sentAt", "receivedAt")}
                                                  | {"testClientReceivedAt": rx}), flush=True)
                self.assertEqual([], call(base, a, "/v1/watch", {"fix": point(400), "waitMs": 0})["events"])
            finally:
                halt(srv, db, thread)
            with self.assertRaises(urllib.error.URLError):
                call(base, a, "/v1/events", data)
            srv, db, thread = launch(port)
            try:
                retry = call(base, a, "/v1/events", data | {"sentAt": milliseconds()})
                self.assertTrue(retry["replayed"])
                self.assertEqual(event["id"], retry["event"]["id"])
                self.assertEqual([event["id"]], [e["id"] for e in watch()["events"]])
                with concurrent.futures.ThreadPoolExecutor(1) as pool:
                    waiting = pool.submit(watch, alert["cursor"], 3000)
                    time.sleep(.05)
                    call(base, a, "/v1/events/" + event["id"], {"state": "resolved"}, "PATCH")
                    self.assertEqual([], waiting.result(4)["events"])
            finally:
                halt(srv, db, thread)


if __name__ == "__main__":
    unittest.main()
