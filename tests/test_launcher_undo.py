"""The launcher undo path remains available on non-target Fire OS devices.

Install/build are API 25 only; undo is deliberately not gated so restore can
hand HOME back to Amazon on any device carrying a legacy DumbTV installation.
"""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class LauncherUndoTest(unittest.TestCase):
    def undo(self, sdk=28):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            state = tmp / "state.json"
            state.write_text(json.dumps({
                "packages": {"tv.dumbtv.home": {"system": False, "enabled": True}},
                "settings": {},
                "sdk": sdk,
                "appops": {"tv.dumbtv.home": {
                    "GET_USAGE_STATS": "allow",
                    "AUTO_START": "allow",
                }},
                "permissions": {"tv.dumbtv.home": {
                    "android.permission.READ_LOGS": True,
                }},
            }))
            log = tmp / "commands.log"
            log.touch()
            env = dict(os.environ, ADB=str(ROOT / "tests" / "fake_tv.py"), ADB_SERIAL="test-tv",
                       FAKE_TV_STATE=str(state), FAKE_TV_LOG=str(log))
            result = subprocess.run(["bash", str(ROOT / "launcher" / "install.sh"), "--undo"],
                                    env=env, capture_output=True, text=True, timeout=60)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            return log.read_text().splitlines(), json.loads(state.read_text())

    def test_undo_is_available_outside_the_launcher_target_api(self):
        commands, _ = self.undo(sdk=28)
        self.assertIn("am force-stop tv.dumbtv.home", commands)
        self.assertIn("am start -a android.intent.action.MAIN -c android.intent.category.HOME", commands)

    def test_all_homesentinel_access_is_reset(self):
        commands, state = self.undo(sdk=25)
        self.assertIn("appops set tv.dumbtv.home GET_USAGE_STATS default", commands)
        self.assertIn("appops set tv.dumbtv.home AUTO_START default", commands)
        self.assertIn("pm revoke tv.dumbtv.home android.permission.READ_LOGS", commands)
        self.assertIn("dumpsys deviceidle whitelist -tv.dumbtv.home", commands)
        self.assertEqual(state["appops"]["tv.dumbtv.home"]["GET_USAGE_STATS"], "default")
        self.assertEqual(state["appops"]["tv.dumbtv.home"]["AUTO_START"], "default")
        self.assertFalse(state["permissions"]["tv.dumbtv.home"]["android.permission.READ_LOGS"])

    def test_undo_does_not_touch_accessibility_or_alarm_settings(self):
        commands, state = self.undo(sdk=25)
        self.assertFalse(any("accessibility" in command or "alarm_manager" in command
                             for command in commands))
        self.assertEqual(state["settings"], {})


if __name__ == "__main__":
    unittest.main()
