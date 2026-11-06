"""Run only against a disposable Android emulator. Never targets an OEM Clock."""
import os
import subprocess
from pathlib import Path

def adb(*args, check=True):
    return subprocess.run(["adb", *args], check=check, stdout=subprocess.PIPE, stderr=subprocess.STDOUT).stdout

def main():
    if adb("shell", "getprop", "ro.kernel.qemu").strip() != b"1" and adb("shell", "getprop", "ro.boot.qemu").strip() != b"1":
        raise SystemExit("A disposable emulator is required")
    evidence = Path("evidence")
    evidence.mkdir(exist_ok=True)
    for pattern in ("**/outputs/apk/qa/app-qa.apk", "**/outputs/apk/debug/clock-fixture-debug.apk",
                    "**/outputs/apk/androidTest/debug/clock-fixture-debug-androidTest.apk"):
        apk, = Path(".").glob(pattern)
        adb("install", "-r", str(apk))
    for pkg in ("dev.alarmoff.qa", "dev.alarmoff.fixture"):
        adb("shell", "pm", "clear", pkg)
    adb("shell", "wm", "dismiss-keyguard")
    adb("shell", "appwidget", "grantbind", "--package", "dev.alarmoff.fixture", "--user", "0")
    result = adb("shell", "am", "instrument", "-w", "-r",
                 "dev.alarmoff.fixture.test/dev.alarmoff.fixture.test.FlowDriver", check=False)
    (evidence / "device-test.txt").write_bytes(result)
    print(result.decode(errors="replace"), flush=True)
    (evidence / "screen.png").write_bytes(adb("exec-out", "screencap", "-p"))
    if b"ALARM_OFF_PASS" not in result:
        (evidence / "logcat.txt").write_bytes(adb("logcat", "-d", "-t", "1800", check=False))
        raise SystemExit("Foreground alarm flow failed")

if __name__ == "__main__":
    main()
