"""Run the device suite, with one clean boot for the Android 7 framework crash."""

import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


BUILD = Path("app/build")
RESULTS = BUILD / "outputs/androidTest-results/connected/debug"


def accessibility_system_crash(results):
    reports = list(results.glob("TEST-*.xml"))
    if len(reports) != 1:
        return False
    try:
        suite = ET.parse(reports[0]).getroot()
    except ET.ParseError:
        return False
    if "System has crashed." not in suite.findtext("system-err", ""):
        return False
    failures = suite.findall("testcase/failure")
    if len(failures) != 1 or failures[0].attrib or (failures[0].text or "").strip() or suite.findall("testcase/error"):
        return False
    case = suite.find("testcase[failure]")
    log_name = f"logcat-{case.get('classname')}-{case.get('name')}.txt"
    for log in results.glob("*/logcat-*.txt"):
        if log.name == log_name:
            lines = log.read_text(encoding="utf-8", errors="replace").splitlines()
            return any("FATAL EXCEPTION IN SYSTEM PROCESS: main" in fatal
                       and "java.lang.NullPointerException" in cause
                       and "ViewParent.requestSendAccessibilityEvent" in cause
                       for fatal, cause in zip(lines, lines[1:]))
    return False


def run():
    classes = os.environ.get("TEST_CLASSES", "").strip() or ",".join(
        os.environ["DEFAULT_TEST_CLASSES"].split()
    )
    command = ["./gradlew", "connectedDebugAndroidTest",
               f"-Pandroid.testInstrumentationRunnerArguments.class={classes}", "--no-daemon"]
    # A fresh AVD's SystemUI tutorial can take focus after the player has rotated.
    subprocess.run(["adb", "shell", "settings", "put", "secure", "immersive_mode_confirmations", "confirmed"],
                   check=True, timeout=30)
    result = subprocess.run(command).returncode
    if result == 0 or os.environ["TEST_API"] != "24" or not accessibility_system_crash(RESULTS):
        return result

    print("::warning::Android 7 system_server crashed dispatching accessibility. Saving the first run and rebooting once.", flush=True)
    shutil.copytree(RESULTS, BUILD / "ci-first-attempt/results")
    subprocess.run(["adb", "reboot"], check=True, timeout=30)
    subprocess.run(["adb", "wait-for-device"], check=True, timeout=120)
    for _ in range(60):
        boot = subprocess.run(["adb", "shell", "getprop", "sys.boot_completed"],
                              capture_output=True, text=True, timeout=10)
        if boot.stdout.strip() == "1":
            # A dead system_server can interrupt Gradle's post-test uninstall.
            installed = subprocess.check_output(["adb", "shell", "pm", "list", "packages", "com.r34v.r34v.debug"],
                                                text=True, timeout=30).splitlines()
            for package in ("com.r34v.r34v.debug.test", "com.r34v.r34v.debug"):
                if f"package:{package}" in installed:
                    subprocess.run(["adb", "uninstall", package], check=True, timeout=30)
            return subprocess.run(command).returncode
        time.sleep(2)
    raise RuntimeError("Android 7 did not finish rebooting")


if __name__ == "__main__":
    sys.exit(run())
