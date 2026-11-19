"""Declining the optional SmartTube step must not fail the main install flow."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class YouTubeCancellationTest(unittest.TestCase):
    def test_declining_smarttube_is_a_successful_skip(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            state = tmp / "state.json"
            state.write_text(json.dumps({
                "packages": {"com.amazon.firetv.youtube": {"system": True, "enabled": True}},
                "settings": {},
                "sdk": 25,
            }))
            log = tmp / "commands.log"
            log.touch()
            conf = tmp / "dumbtv.conf"
            conf.write_text("YOUTUBE=1\\n")
            env = dict(os.environ, ADB=str(ROOT / "tests/fake_tv.py"),
                       ADB_SERIAL="test-tv", FAKE_TV_STATE=str(state),
                       FAKE_TV_LOG=str(log), CONF=str(conf))
            result = subprocess.run(
                ["bash", str(ROOT / "scripts/youtube.sh")],
                input="n\\n", env=env, capture_output=True, text=True, timeout=30,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("installation cancelled", result.stderr)
            self.assertFalse(any("install" in line or "disable-user" in line
                                 for line in log.read_text().splitlines()))
            self.assertTrue(json.loads(state.read_text())["packages"]["com.amazon.firetv.youtube"]["enabled"])


if __name__ == "__main__":
    unittest.main()
