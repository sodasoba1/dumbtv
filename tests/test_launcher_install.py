"""Exercise launcher installation against a fake API 25 Fire TV; no hardware or network."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class LauncherInstallTest(unittest.TestCase):
    def install(self, appops_refused=False, read_logs_refused=False):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            launcher = tmp / "launcher"
            scripts = tmp / "scripts"
            bindir = tmp / "bin"
            launcher.mkdir()
            scripts.mkdir()
            bindir.mkdir()
            shutil.copy(ROOT / "launcher/install.sh", launcher / "install.sh")
            shutil.copy(ROOT / "scripts/home.sh", scripts / "home.sh")
            (launcher / "version.env").write_text("VERSION=1.11\n")
            (launcher / "build.sh").write_text(
                "#!/usr/bin/env bash\nmkdir -p build\nprintf apk > build/dumbtv-home.apk\n")
            (bindir / "sleep").write_text("#!/bin/sh\nexit 0\n")
            (launcher / "build.sh").chmod(0o755)
            (bindir / "sleep").chmod(0o755)
            state = tmp / "state.json"
            state.write_text(json.dumps({
                "packages": {"tv.dumbtv.home": {"system": False, "enabled": True}},
                "settings": {},
                "sdk": 25,
                "keeper_running": True,
                "appops_refused": appops_refused,
                "read_logs_refused": read_logs_refused,
                "appops": {"tv.dumbtv.home": {}},
            }))
            log = tmp / "commands.log"
            log.touch()
            env = dict(os.environ, ADB=str(ROOT / "tests/fake_tv.py"), ADB_SERIAL="test-tv",
                       FAKE_TV_STATE=str(state), FAKE_TV_LOG=str(log),
                       PATH=str(bindir) + os.pathsep + os.environ["PATH"])
            result = subprocess.run(["bash", str(launcher / "install.sh"), "--build"],
                                    env=env, capture_output=True, text=True, timeout=60)
            return result, log.read_text().splitlines(), json.loads(state.read_text())

    def test_install_grants_keeper_standby_and_usage_access(self):
        result, commands, state = self.install()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("appops set tv.dumbtv.home GET_USAGE_STATS allow", commands)
        self.assertIn("appops set tv.dumbtv.home AUTO_START allow", commands)
        self.assertIn("dumpsys deviceidle whitelist +tv.dumbtv.home", commands)
        self.assertIn("pm grant tv.dumbtv.home android.permission.READ_LOGS", commands)
        self.assertEqual(state["appops"]["tv.dumbtv.home"]["GET_USAGE_STATS"], "allow")
        self.assertEqual(state["appops"]["tv.dumbtv.home"]["AUTO_START"], "allow")
        self.assertTrue(state["permissions"]["tv.dumbtv.home"]["android.permission.READ_LOGS"])
        self.assertIn("standby start: AUTO_START allowed", result.stdout)
        self.assertIn("READ_LOGS granted and verified", result.stdout)

    def test_install_stops_if_fire_os_refuses_standby_access(self):
        result, commands, _ = self.install(appops_refused=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("appops set tv.dumbtv.home AUTO_START allow", commands)
        self.assertIn("Fire OS refused Keeper's AUTO_START permission", result.stderr)

    def test_install_fails_if_adb_refuses_read_logs(self):
        result, commands, state = self.install(read_logs_refused=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("pm grant tv.dumbtv.home android.permission.READ_LOGS", commands)
        self.assertIn("could not grant Keeper READ_LOGS", result.stderr)
        self.assertFalse(state.get("permissions", {}).get("tv.dumbtv.home", {}).get(
            "android.permission.READ_LOGS", False))


if __name__ == "__main__":
    unittest.main()
