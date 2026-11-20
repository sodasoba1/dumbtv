"""Regression tests for the API 25 HomeSentinel verification path; never contact hardware."""
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
    def run_verify(self, *, sdk="25", homesentinel=True, takeover_gate="1", usage="allow", auto_start="allow",
                   read_logs=True, launcher="1", launcher_package=None, extra=None,
                   changed_settings=None, disabled="", installed="", data_saver="enabled",
                   screensaver="null", home="tv.dumbtv.home/.Home", alexa=1,
                   changed_alexa_settings=None):
        version = RELEASE if launcher_package is None else launcher_package
        package_dump = f"    versionName={version}\n"
        if read_logs:
            package_dump += "    android.permission.READ_LOGS: granted=true\n"
        resumed = f"  mResumedActivity: ActivityRecord{{1 u0 {home} t1}}"
        replies = {
            "getprop ro.build.version.sdk": {"out": sdk},
            "cmd package help": {"out": "Package manager commands: path [--user USER_ID] PACKAGE"},
            "dumpsys activity activities": {"out": resumed},
            "dumpsys activity services tv.dumbtv.home": {
                "out": "ServiceRecord tv.dumbtv.home/.HomeSentinel isForeground=true" if homesentinel else ""
            },
            "settings get global dumbtv_homesentinel_enabled": {"out": takeover_gate},
            "appops get tv.dumbtv.home GET_USAGE_STATS": {"out": f"GET_USAGE_STATS: {usage}"},
            "appops get tv.dumbtv.home AUTO_START": {"out": f"AUTO_START: {auto_start}"},
            "dumpsys package tv.dumbtv.home": {"out": package_dump},
            "pm list packages -d": {"out": disabled},
            "pm list packages": {"out": installed},
            "settings list secure; settings list global": {
                "out": self.applied_settings(**(changed_settings or {}), alexa=alexa,
                                             changed_alexa=(changed_alexa_settings or {}))},
            "cmd netpolicy get restrict-background": {"out": f"Restrict background status: {data_saver}"},
            "settings get secure screensaver_components": {"out": screensaver},
        }
        replies.update(extra or {})
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            data, log = tmp / "responses.json", tmp / "calls.jsonl"
            data.write_text(json.dumps(replies))
            conf = tmp / "test.conf"
            conf.write_text("ALEXA=0\n" if alexa == 0 else "")
            env = dict(os.environ, ADB=str(ROOT / "tests/fake_verify_adb.py"),
                       SERIAL="test-tv", CONF=str(conf),
                       LAUNCHER=launcher, KEEP_APPS="", FAKE_ADB_LOG=str(log),
                       FAKE_ADB_RESPONSES=str(data))
            result = subprocess.run(["bash", str(ROOT / "scripts/verify.sh")],
                                    env=env, text=True, capture_output=True)
            calls = [json.loads(line) for line in log.read_text().splitlines()]
        self.assertNotIn("unexpected command", result.stdout + result.stderr)
        return result, calls

    @staticmethod
    def applied_settings(alexa=1, changed_alexa=None, **changed):
        rows = []
        sources = [ROOT / "data/fireos/settings.txt"]
        if alexa == 0:
            sources.append(ROOT / "data/fireos/settings.alexa.txt")
        for source in sources:
            for line in source.read_text().splitlines():
                line = line.split("#")[0].split()
                if len(line) == 3:
                    overrides = (changed_alexa or {}) if source.name == "settings.alexa.txt" else changed
                    rows.append(f"{line[1]}={overrides.get(line[1], line[2])}")
        return "\n".join(rows)

    def test_api25_homesentinel_and_standby_permissions_pass(self):
        result, _ = self.run_verify()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("HomeSentinel armed", result.stdout)
        self.assertIn("UsageStats AppOp allowed", result.stdout)
        self.assertIn("optional AUTO_START is allowed", result.stdout)
        self.assertIn("READ_LOGS granted", result.stdout)

    def test_disabled_takeover_gate_fails_verification(self):
        result, _ = self.run_verify(takeover_gate="0")
        self.assertEqual(result.returncode, 1)
        self.assertIn("expected 1, got 0", result.stdout)

    def test_missing_takeover_gate_fails_verification(self):
        result, _ = self.run_verify(takeover_gate="null")
        self.assertEqual(result.returncode, 1)
        self.assertIn("expected 1, got null", result.stdout)

    def test_missing_homesentinel_is_a_failure(self):
        result, _ = self.run_verify(homesentinel=False)
        self.assertEqual(result.returncode, 1)
        self.assertIn("HomeSentinel not running", result.stdout)

    def test_running_homesentinel_does_not_pass_when_amazon_home_is_foreground(self):
        result, _ = self.run_verify(home="com.amazon.tv.launcher/.ui.HomeActivity_vNext")
        self.assertEqual(result.returncode, 1)
        self.assertIn("FAIL", result.stdout)
        self.assertIn("Amazon Home is foreground (com.amazon.tv.launcher", result.stdout)

    def test_unknown_foreground_is_not_reported_as_armed(self):
        result, _ = self.run_verify(home="")
        self.assertEqual(result.returncode, 1)
        self.assertIn("could not determine the foreground activity", result.stdout)

    def test_running_homesentinel_passes_with_an_ordinary_app_in_foreground(self):
        result, _ = self.run_verify(home="com.netflix.ninja/.MainActivity")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("HomeSentinel armed; foreground is com.netflix.ninja", result.stdout)

    def test_missing_usage_stats_is_a_failure(self):
        result, _ = self.run_verify(usage="default")
        self.assertEqual(result.returncode, 1)
        self.assertIn("usage-access", result.stdout)

    def test_missing_auto_start_is_a_failure(self):
        result, _ = self.run_verify(auto_start="default")
        self.assertEqual(result.returncode, 1)
        self.assertIn("standby-start", result.stdout)
        self.assertIn("AUTO_START", result.stdout)

    def test_unsupported_auto_start_is_not_a_failure(self):
        result, _ = self.run_verify(auto_start="Unknown operation string: AUTO_START")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("skip", result.stdout)
        self.assertIn("AUTO_START unsupported", result.stdout)

    def test_missing_read_logs_is_a_failure_even_when_usage_stats_are_enabled(self):
        result, _ = self.run_verify(read_logs=False)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("FAIL", result.stdout)
        self.assertIn("READ_LOGS not granted", result.stdout)

    def test_non_api25_device_skips_homesentinel_checks(self):
        result, calls = self.run_verify(sdk="28")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("skip", result.stdout)
        self.assertIn("API 25", result.stdout)
        self.assertFalse(any("tv.dumbtv.home" in " ".join(call) for call in calls))

    def test_launcher_opt_out_skips_homesentinel_checks(self):
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
        result, _ = self.run_verify(installed="package:com.amazon.hedwig\npackage:com.netflix.ninja")
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("enabled again: com.amazon.hedwig", result.stdout)

    def test_data_saver_disabled_is_reported(self):
        result, _ = self.run_verify(data_saver="disabled")
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("data-saver", result.stdout)
        self.assertIn("off", result.stdout)

    def test_alexa_settings_are_verified_when_alexa_is_disabled(self):
        result, _ = self.run_verify(alexa=0, changed_alexa_settings={"voice_disabled": "0"})
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("back to Amazon's values: voice_disabled=0", result.stdout)

    def test_protected_user_disabled_package_is_detected_even_if_disabled_listing_omits_it(self):
        result, _ = self.run_verify(
            installed="package:com.amazon.firebat",
            extra={"dumpsys package com.amazon.firebat": {
                "out": "  User 0: installed=true enabled=3"}})
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("protected", result.stdout)
        self.assertIn("com.amazon.firebat", result.stdout)

    def test_user_disabled_never_disable_package_fails_verification(self):
        result, _ = self.run_verify(
            installed="package:com.amazon.firebat",
            disabled="package:com.amazon.firebat",
            extra={"dumpsys package com.amazon.firebat": {
                "out": "  User 0: installed=true enabled=3"}})
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("FAIL", result.stdout)
        self.assertIn("protected", result.stdout)
        self.assertIn("com.amazon.firebat", result.stdout)

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
