"""Preflight snapshots are device-scoped and preserve the first baseline."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class PreflightTest(unittest.TestCase):
    def run_preflight(self, state, report, serial="test-tv:5555"):
        state_path = Path(state)
        log = state_path.parent / "commands.log"
        log.touch(exist_ok=True)
        conf = state_path.parent / "dumbtv.conf"
        conf.write_text("")
        env = dict(os.environ, ADB=str(ROOT / "tests/fake_tv.py"),
                   ADB_SERIAL=serial, FAKE_TV_STATE=str(state_path),
                   FAKE_TV_LOG=str(log), REPORT=str(report), CONF=str(conf))
        result = subprocess.run(
            ["bash", str(ROOT / "scripts/preflight.sh")],
            env=env, capture_output=True, text=True, timeout=30)
        return result, log.read_text().splitlines()

    def test_first_preflight_captures_device_identity_and_original_state(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            state = tmp / "state.json"
            state.write_text(json.dumps({
                "sdk": 25, "manufacturer": "Amazon",
                "props": {"ro.product.model": "AFTMM", "ro.build.display.id": "build-a"},
                "packages": {
                    "com.amazon.device.metrics": {"system": True, "enabled": True},
                    "com.netflix.ninja": {"system": False, "enabled": True},
                },
                "settings": {
                    "secure/screen_timeout": "300",
                    "global/animator_duration_scale": "1.0",
                },
            }))
            report = tmp / "report"
            result, _ = self.run_preflight(state, report)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("serial=test-tv:5555", (report / "device.txt").read_text())
            self.assertEqual((report / "settings-before-secure.txt").read_text(), "screen_timeout=300\n")
            self.assertEqual((report / "settings-before-global.txt").read_text(),
                             "animator_duration_scale=1.0\n")
            self.assertIn("com.netflix.ninja\tenabled", (report / "packages-before.txt").read_text())

    def test_repeat_preflight_does_not_replace_the_original_baseline(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            state = tmp / "state.json"
            state.write_text(json.dumps({
                "sdk": 25, "manufacturer": "Amazon",
                "props": {"ro.product.model": "AFTMM"},
                "packages": {"com.netflix.ninja": {"system": False, "enabled": True}},
                "settings": {"secure/screen_timeout": "300", "global/animator_duration_scale": "1.0"},
            }))
            report = tmp / "report"
            first, _ = self.run_preflight(state, report)
            self.assertEqual(first.returncode, 0, first.stdout + first.stderr)
            before = (report / "settings-before-secure.txt").read_text()
            data = json.loads(state.read_text())
            data["settings"]["secure/screen_timeout"] = "60"
            data["packages"]["com.netflix.ninja"]["enabled"] = False
            state.write_text(json.dumps(data))
            second, _ = self.run_preflight(state, report)
            self.assertEqual(second.returncode, 0, second.stdout + second.stderr)
            self.assertEqual((report / "settings-before-secure.txt").read_text(), before)
            self.assertIn("screen_timeout=300", before)
            self.assertIn("com.netflix.ninja\tenabled",
                          (report / "packages-before.txt").read_text())

    def test_preflight_refuses_a_report_owned_by_another_adb_device(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            state = tmp / "state.json"
            state.write_text(json.dumps({
                "sdk": 25, "manufacturer": "Amazon",
                "props": {"ro.product.model": "AFTMM"},
                "packages": {"com.netflix.ninja": {"system": False, "enabled": True}},
                "settings": {"secure/screen_timeout": "300", "global/animator_duration_scale": "1.0"},
            }))
            report = tmp / "report"
            first, _ = self.run_preflight(state, report)
            self.assertEqual(first.returncode, 0, first.stdout + first.stderr)
            second, _ = self.run_preflight(state, report, serial="other-tv:5555")
            self.assertNotEqual(second.returncode, 0)
            self.assertIn("different ADB device", second.stderr)

    def test_preflight_refuses_an_incomplete_snapshot(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            state = tmp / "state.json"
            state.write_text(json.dumps({
                "sdk": 25, "manufacturer": "Amazon",
                "props": {"ro.product.model": "AFTMM"},
                "packages": {"com.netflix.ninja": {"system": False, "enabled": True}},
                "settings": {"secure/screen_timeout": "300", "global/animator_duration_scale": "1.0"},
            }))
            report = tmp / "report"
            report.mkdir()
            (report / "device.txt").write_text(
                "format=1\nserial=test-tv:5555\nmodel=AFTMM\nbuild=\n")
            (report / "settings-before-secure.txt").write_text("screen_timeout=300\n")
            result, _ = self.run_preflight(state, report)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("incomplete settings snapshot", result.stderr)
            self.assertFalse((report / "settings-before-global.txt").exists())


if __name__ == "__main__":
    unittest.main()
