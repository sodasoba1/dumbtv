"""Lockdown snapshots and applies only the policy it owns."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class LockdownTest(unittest.TestCase):
    def test_incomplete_appops_snapshot_is_not_reconstructed(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            state = tmp / "state.json"
            initial = {
                "sdk": 25,
                "manufacturer": "Amazon",
                "packages": {
                    "com.amazon.device.metrics": {
                        "system": True, "enabled": True, "enabled_state": 0
                    }
                },
                "appops": {
                    "com.amazon.device.metrics": {"RUN_IN_BACKGROUND": "foreground"}
                },
                "network_blacklist": [],
                "restrict_background": "enabled",
                "props": {"ro.product.model": "AFTMM", "ro.build.display.id": "test-build"},
            }
            state.write_text(json.dumps(initial))
            report = tmp / "report"
            report.mkdir()
            (report / "device.txt").write_text(
                "format=2\nserial_sha256=" + hashlib.sha256(b"test-tv:5555").hexdigest()
                + "\nmodel=AFTMM\nbuild=test-build\n")
            (report / "lockdown-before.tsv").write_text(
                "com.amazon.device.metrics\t10050\tforeground\tnot-applicable\t0\n")
            conf = tmp / "dumbtv.conf"
            conf.write_text("")
            env = dict(os.environ, ADB=str(ROOT / "tests/fake_tv.py"),
                       ADB_SERIAL="test-tv:5555", FAKE_TV_STATE=str(state),
                       REPORT=str(report), CONF=str(conf))
            result = subprocess.run(
                ["bash", str(ROOT / "scripts/lockdown.sh"), "-y"],
                env=env, capture_output=True, text=True, timeout=30)
            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("incomplete lockdown snapshot", result.stderr)
            after = json.loads(state.read_text())
            self.assertEqual(after["restrict_background"], "enabled")
            self.assertEqual(after["appops"]["com.amazon.device.metrics"]["RUN_IN_BACKGROUND"],
                             "foreground")
            self.assertEqual(after["network_blacklist"], [])

    def test_incomplete_data_saver_snapshot_is_not_reconstructed(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            state = tmp / "state.json"
            initial = {
                "sdk": 25,
                "manufacturer": "Amazon",
                "packages": {
                    "com.amazon.device.metrics": {
                        "system": True, "enabled": True, "enabled_state": 0
                    }
                },
                "appops": {
                    "com.amazon.device.metrics": {"RUN_IN_BACKGROUND": "foreground"}
                },
                "network_blacklist": [],
                "restrict_background": "disabled",
                "props": {"ro.product.model": "AFTMM", "ro.build.display.id": "test-build"},
            }
            state.write_text(json.dumps(initial))
            report = tmp / "report"
            report.mkdir()
            (report / "device.txt").write_text(
                "format=2\nserial_sha256=" + hashlib.sha256(b"test-tv:5555").hexdigest()
                + "\nmodel=AFTMM\nbuild=test-build\n")
            (report / "data-saver-before.txt").write_text("disabled\n")
            conf = tmp / "dumbtv.conf"
            conf.write_text("")
            env = dict(os.environ, ADB=str(ROOT / "tests/fake_tv.py"),
                       ADB_SERIAL="test-tv:5555", FAKE_TV_STATE=str(state),
                       REPORT=str(report), CONF=str(conf))
            result = subprocess.run(
                ["bash", str(ROOT / "scripts/lockdown.sh"), "-y"],
                env=env, capture_output=True, text=True, timeout=30)
            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("incomplete lockdown snapshot", result.stderr)
            after = json.loads(state.read_text())
            self.assertEqual(after["restrict_background"], "disabled")
            self.assertEqual(after["appops"]["com.amazon.device.metrics"]["RUN_IN_BACKGROUND"],
                             "foreground")
            self.assertEqual(after["network_blacklist"], [])

    def test_original_appops_blacklist_and_data_saver_are_snapshotted_once(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            state = tmp / "state.json"
            state.write_text(json.dumps({
                "sdk": 25,
                "manufacturer": "Amazon",
                "packages": {
                    "com.amazon.device.metrics": {
                        "system": True, "enabled": True, "enabled_state": 0
                    }
                },
                "appops": {
                    "com.amazon.device.metrics": {"RUN_IN_BACKGROUND": "foreground"}
                },
                "network_blacklist": [10050],
                "restrict_background": "disabled",
                "props": {"ro.product.model": "AFTMM", "ro.build.display.id": "test-build"},
            }))
            report = tmp / "report"
            report.mkdir()
            (report / "device.txt").write_text(
                "format=2\nserial_sha256=" + hashlib.sha256(b"test-tv:5555").hexdigest()
                + "\nmodel=AFTMM\nbuild=test-build\n")
            conf = tmp / "dumbtv.conf"
            conf.write_text("")
            log = tmp / "commands.log"
            log.touch()
            env = dict(os.environ, ADB=str(ROOT / "tests/fake_tv.py"),
                       ADB_SERIAL="test-tv:5555", FAKE_TV_STATE=str(state),
                       FAKE_TV_LOG=str(log), REPORT=str(report), CONF=str(conf))
            command = ["bash", str(ROOT / "scripts/lockdown.sh"), "-y"]

            first = subprocess.run(command, env=env, capture_output=True, text=True, timeout=30)
            self.assertEqual(first.returncode, 0, first.stdout + first.stderr)
            original_ops = (report / "lockdown-before.tsv").read_text()
            original_data_saver = (report / "data-saver-before.txt").read_text()
            after_first = json.loads(state.read_text())
            self.assertEqual(original_ops, "com.amazon.device.metrics\t10050\tforeground\tnot-applicable\t1\n")
            self.assertEqual(original_data_saver, "disabled\n")
            self.assertEqual(after_first["appops"]["com.amazon.device.metrics"]["RUN_IN_BACKGROUND"], "ignore")
            self.assertIn(10050, after_first["network_blacklist"])
            self.assertEqual(after_first["restrict_background"], "enabled")

            second = subprocess.run(command, env=env, capture_output=True, text=True, timeout=30)
            self.assertEqual(second.returncode, 0, second.stdout + second.stderr)
            self.assertEqual((report / "lockdown-before.tsv").read_text(), original_ops)
            self.assertEqual((report / "data-saver-before.txt").read_text(), original_data_saver)


if __name__ == "__main__":
    unittest.main()
