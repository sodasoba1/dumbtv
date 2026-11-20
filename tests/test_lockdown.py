"""Lockdown snapshots and applies only the policy it owns."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class LockdownTest(unittest.TestCase):
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
                "format=1\nserial=test-tv:5555\nmodel=AFTMM\nbuild=test-build\n")
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
            self.assertEqual(original_ops, "com.amazon.device.metrics\\t10050\\tforeground\\tnot-applicable\\t1\\n")
            self.assertEqual(original_data_saver, "disabled\\n")
            self.assertEqual(after_first["appops"]["com.amazon.device.metrics"]["RUN_IN_BACKGROUND"], "ignore")
            self.assertIn(10050, after_first["network_blacklist"])
            self.assertEqual(after_first["restrict_background"], "enabled")

            second = subprocess.run(command, env=env, capture_output=True, text=True, timeout=30)
            self.assertEqual(second.returncode, 0, second.stdout + second.stderr)
            self.assertEqual((report / "lockdown-before.tsv").read_text(), original_ops)
            self.assertEqual((report / "data-saver-before.txt").read_text(), original_data_saver)


if __name__ == "__main__":
    unittest.main()
