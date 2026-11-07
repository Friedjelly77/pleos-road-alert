"""Exercise the installed APK over ADB with an independent local participant."""
import argparse
import json
import re
import subprocess
import time
import queue
import threading
import xml.etree.ElementTree as ET
from pathlib import Path
from participant import call, credentials, milliseconds, point, report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    args.evidence.mkdir(parents=True, exist_ok=True)
    base = "http://127.0.0.1:8787"
    peer = credentials()

    def adb(*cmd):
        return subprocess.check_output([args.adb, "-s", args.serial, *cmd], timeout=30)

    def node(label, limit=35):
        deadline = time.monotonic() + limit
        last = []
        while time.monotonic() < deadline:
            dump = adb("shell", "uiautomator", "dump", "/data/local/tmp/road-alert-window.xml")
            if b"dumped to" not in dump:
                time.sleep(.25)
                continue
            root = ET.fromstring(adb("exec-out", "cat", "/data/local/tmp/road-alert-window.xml"))
            nodes = [n for n in root.iter("node") if n.get("package") == "kr.roadalert.pleos"]
            last = [n.get("text") for n in nodes if n.get("text")]
            for n in nodes:
                if n.get("text") == label:
                    return n
            time.sleep(.25)
        raise AssertionError((label, last))

    def tap(label):
        left, top, right, bottom = map(int, re.findall(r"\d+", node(label).get("bounds")))
        adb("shell", "input", "tap", str((left + right)//2), str((top + bottom)//2))

    def screenshot(name):
        (args.evidence / name).write_bytes(adb("exec-out", "screencap", "-p"))

    adb("reverse", "tcp:8787", "tcp:8787")
    adb("shell", "am", "start", "-n", "kr.roadalert.pleos/kr.roadalert.MainActivity")
    node("연결됨")
    tap("데모 설정"); tap("A · 제보 지점"); tap("메인으로")
    tap("상황 제보"); tap("사고 제보")
    node("서버 접수 완료 · 진행 중")
    events = call(base, peer, "/v1/watch", dict(fix=point(400), waitMs=0))["events"]
    event = next(e for e in events if e["kind"] == "crash")
    print("APK_TO_PEER", event["id"], event["receivedAt"], milliseconds(), flush=True)
    tap("최근 제보 상태"); tap("해소 제보"); node("사고 제보   ·   해소"); tap("메인으로")
    assert event["id"] not in [e["id"] for e in call(base, peer, "/v1/watch", dict(fix=point(400), waitMs=0))["events"]]
    tap("데모 설정"); tap("B · 600 m 전방 접근"); tap("메인으로")
    logs = subprocess.Popen([args.adb, "-s", args.serial, "logcat", "-v", "raw", "-T", "1",
                             "-s", "RoadAlertTrace:I"], stdout=subprocess.PIPE)
    seen = queue.Queue()
    def collect():
        for line in logs.stdout:
            if line.startswith(b"{"):
                seen.put((time.monotonic_ns(), json.loads(line)))
    threading.Thread(target=collect, daemon=True).start()
    start = time.monotonic_ns()
    e = call(base, peer, "/v1/events", report(point()))["event"]
    try:
        node("전방 사고 제보")
        screenshot("approach.png")
        while True:
            end, metrics = seen.get(timeout=15)
            if metrics["eventId"] == e["id"]:
                break
        metrics["hostCreateToDrawLogMs"] = round((end - start) / 1e6, 1)
        (args.evidence / "trace.json").write_text(json.dumps(metrics, indent=2), encoding="utf-8")
        print("PEER_TO_APK", metrics, flush=True)
    finally:
        logs.terminate()
        logs.wait(timeout=5)
        call(base, peer, "/v1/events/" + e["id"], {"state":"resolved"}, "PATCH")
    node("전방 제보 없음")
    tap("데모 설정"); tap("A · 제보 지점"); tap("메인으로")
    adb("reverse", "--remove", "tcp:8787")
    try:
        tap("상황 제보"); tap("도로 위험 제보"); node("연결 끊김 · 저장 후 재시도 중")
        assert not call(base, peer, "/v1/watch", dict(fix=point(400), waitMs=0))["events"]
    finally:
        adb("reverse", "tcp:8787", "tcp:8787")
    node("서버 접수 완료 · 진행 중")
    after = call(base, peer, "/v1/watch", dict(fix=point(400), waitMs=0))["events"]
    assert len(after) == 1 and after[0]["kind"] == "hazard"
    tap("최근 제보 상태"); tap("제보 취소"); node("도로 위험 제보   ·   취소"); tap("메인으로")
    print("APK_OFFLINE_RETRY_AND_CANCEL PASS", flush=True)
    print("PASS", flush=True)


if __name__ == "__main__":
    main()
