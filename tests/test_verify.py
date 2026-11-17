"""Regression tests for the API 25 Keeper verification path; never contact hardware."""
import json
import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RELEASE = re.search(r"^VERSION=(\S+)", (ROOT / "launcher/version.env").read_text(), re.M).group(1)


class VerifyTests(unittest.TestCase):
    def run_verify(self, *, sdk="25", keeper=True, usage="allow", auto_start="allow",
                   read_logs=True, launcher="1", launcher_package=None, extra=None,
                   changed_settings=None, disabled="", installed="", data_saver="enabled",
                   screensaver="null"):
        version = RELEASE if launcher_package is None else launcher_package
        package_dump = f"    versionName={version}\n"
        if read_logs:
            package_dump += "    android.permission.READ_LOGS: granted=true\n"
        resumed = "  mResumedActivity: ActivityRecord{1 u0 tv.dumbtv.home/.Home t1}"
        replies = {
            "getprop ro.build.version.sdk": {"out": sdk},
            "cmd package help": {"out": "Package manager commands: path [--user USER_ID] PACKAGE"},
            "dumpsys activity activities": {"out": resumed},
            "dumpsys activity services tv.dumbtv.home": {
                "out": "ServiceRecord tv.dumbtv.home/.Keeper isForeground=true" if keeper else ""
            },
            "appops get tv.dumbtv.home GET_USAGE_STATS": {"out": f"GET_USAGE_STATS: {usage}"},
            "appops get tv.dumbtv.home AUTO_START": {"out": f"AUTO_START: {auto_start}"},
            "dumpsys package tv.dumbtv.home": {"out": package_dump},
            "pm list packages -d": {"out": disabled},
            "pm list packages": {"out": installed},
            "settings list secure; settings list global": {"out": self.applied_settings(**(changed_settings or {}))},
            "cmd netpolicy get restrict-background": {"out": f"Restrict background status: {data_saver}"},
            "settings get secure screensaver_components": {"out": screensaver},
        }
        replies.update(extra or {})
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            data, log = tmp / "responses.json", tmp / "calls.jsonl"
            data.write_text(json.dumps(replies))
            env = dict(os.environ, ADB=str(ROOT / "tests/fake_verify_adb.py"),
                       SERIAL="test-tv", CONF=str(tmp / "absent.conf"),
                       LAUNCHER=launcher, KEEP_APPS="", FAKE_ADB_LOG=str(log),
                       FAKE_ADB_RESPONSES=str(data))
            result = subprocess.run(["bash", str(ROOT / "scripts/verify.sh")],
                                    env=env, text=True, capture_output=True)
            calls = [json.loads(line) for line in log.read_text().splitlines()]
        self.assertNotIn("unexpected command", result.stdout + result.stderr)
        return result, calls

    @staticmethod
    def applied_settings(**changed):
        rows = []
        for line in (ROOT / "data/fireos/settings.txt").read_text().splitlines():
            line = line.split("#")[0].split()
            if len(line) == 3:
                rows.append(f"{line[1]}={changed.get(line[1], line[2])}")
        return "\n".join(rows)

    def test_api25_keeper_and_standby_permissions_pass(self):
        result, _ = self.run_verify()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("Keeper armed", result.stdout)
        self.assertIn("UsageStats AppOp allowed", result.stdout)
        self.assertIn("Keeper AUTO_START allowed", result.stdout)
        self.assertIn("READ_LOGS granted", result.stdout)

    def test_missing_keeper_is_a_failure(self):
        result, _ = self.run_verify(keeper=False)
        self.assertEqual(result.returncode, 1)
        self.assertIn("Keeper not running", result.stdout)

    def test_missing_usage_stats_is_a_failure(self):
        result, _ = self.run_verify(usage="default")
        self.assertEqual(result.returncode, 1)
        self.assertIn("usage-access", result.stdout)

    def test_missing_auto_start_is_a_failure(self):
        result, _ = self.run_verify(auto_start="default")
        self.assertEqual(result.returncode, 1)
        self.assertIn("standby-start", result.stdout)
        self.assertIn("AUTO_START", result.stdout)

    def test_read_logs_is_optional_when_usage_stats_remain_enabled(self):
        result, _ = self.run_verify(read_logs=False)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("READ_LOGS unavailable", result.stdout)

    def test_non_api25_device_is_rejected_before_keeper_checks(self):
        result, calls = self.run_verify(sdk="28")
        self.assertEqual(result.returncode, 1)
        self.assertIn("only Fire OS 6 / API 25", result.stdout)
        self.assertFalse(any("AUTO_START" in " ".join(call) for call in calls))

    def test_launcher_opt_out_skips_keeper_checks(self):
        result, calls = self.run_verify(launcher="0")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("skip", result.stdout)
        self.assertFalse(any("tv.dumbtv.home" in " ".join(call) for call in calls))

    def test_a_disabled_screensaver_is_reported(self):
        result, _ = self.run_verify(
            disabled="package:com.amazon.ftv.screensaver\npackage:com.amazon.tv.acr",
            screensaver="com.amazon.ftv.screensaver/.app.services.ScreensaverService")
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("2 disabled", result.stdout)
        self.assertIn("TV sleeps at the timeout", result.stdout)

    def test_a_privacy_setting_reset_is_reported(self):
        result, _ = self.run_verify(changed_settings={"limit_ad_tracking": "0"})
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("back to Amazon's values: limit_ad_tracking=0", result.stdout)

    def test_a_disabled_package_enabled_again_is_reported(self):
        result, _ = self.run_verify(installed="package:com.amazon.tv.acr\npackage:com.netflix.ninja")
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("enabled again: com.amazon.tv.acr", result.stdout)

    def test_data_saver_disabled_is_reported(self):
        result, _ = self.run_verify(data_saver="disabled")
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("data-saver", result.stdout)
        self.assertIn("off", result.stdout)

    def test_old_launcher_version_is_a_failure(self):
        result, _ = self.run_verify(launcher_package="    versionName=1.2")
        self.assertEqual(result.returncode, 1)
        self.assertIn("FAIL", result.stdout)
        self.assertIn("run ./scripts/launcher.sh -y", result.stdout)

    def test_newer_launcher_version_is_accepted(self):
        result, _ = self.run_verify(launcher_package="    versionName=99.0")
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("newer than", result.stdout)


if __name__ == "__main__":
    unittest.main()
