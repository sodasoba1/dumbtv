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
    def install(self, appops_refused=False, auto_start_unsupported=False, read_logs_refused=False, read_logs_not_recorded=False, installer_args=None):
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
                "homesentinel_running": True,
                "appops_refused": appops_refused,
                "auto_start_unsupported": auto_start_unsupported,
                "read_logs_refused": read_logs_refused,
                "read_logs_not_recorded": read_logs_not_recorded,
                "appops": {"tv.dumbtv.home": {}},
            }))
            log = tmp / "commands.log"
            log.touch()
            env = dict(os.environ, ADB=str(ROOT / "tests/fake_tv.py"), ADB_SERIAL="test-tv",
                       FAKE_TV_STATE=str(state), FAKE_TV_LOG=str(log),
                       PATH=str(bindir) + os.pathsep + os.environ["PATH"])
            apk = tmp / "supplied-dumbtv.apk"
            apk.write_bytes(b"fake apk")
            args = ["--build"] if installer_args is None else installer_args
            args = [str(apk) if value == "{APK}" else value for value in args]
            result = subprocess.run(["bash", str(launcher / "install.sh"), *args],
                                    env=env, capture_output=True, text=True, timeout=60)
            return result, log.read_text().splitlines(), json.loads(state.read_text())

    def test_supplied_apk_install_also_grants_homesentinel_permissions(self):
        result, commands, state = self.install(installer_args=["--apk", "{APK}"])
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue(any(command.startswith("install -r ") for command in commands), commands)
        self.assertIn("appops set tv.dumbtv.home GET_USAGE_STATS allow", commands)
        self.assertIn("pm grant tv.dumbtv.home android.permission.READ_LOGS", commands)
        self.assertEqual(state["appops"]["tv.dumbtv.home"]["GET_USAGE_STATS"], "allow")
        self.assertTrue(state["permissions"]["tv.dumbtv.home"]["android.permission.READ_LOGS"])

    def test_permissions_only_repairs_homesentinel_without_reinstalling(self):
        result, commands, state = self.install(installer_args=["--permissions-only"])
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertFalse(any(command.startswith("install ") for command in commands), commands)
        self.assertIn("appops set tv.dumbtv.home GET_USAGE_STATS allow", commands)
        self.assertIn("pm grant tv.dumbtv.home android.permission.READ_LOGS", commands)
        self.assertTrue(state["permissions"]["tv.dumbtv.home"]["android.permission.READ_LOGS"])

    def test_permissions_only_requires_existing_install(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            launcher = tmp / "launcher"; scripts = tmp / "scripts"; bindir = tmp / "bin"
            launcher.mkdir(); scripts.mkdir(); bindir.mkdir()
            shutil.copy(ROOT / "launcher/install.sh", launcher / "install.sh")
            shutil.copy(ROOT / "scripts/home.sh", scripts / "home.sh")
            (launcher / "version.env").write_text("VERSION=1.11\n")
            state = tmp / "state.json"
            state.write_text(json.dumps({"packages": {}, "settings": {}, "sdk": 25}))
            log = tmp / "commands.log"; log.touch()
            env = dict(os.environ, ADB=str(ROOT / "tests/fake_tv.py"), ADB_SERIAL="test-tv",
                       FAKE_TV_STATE=str(state), FAKE_TV_LOG=str(log),
                       PATH=str(bindir) + os.pathsep + os.environ["PATH"])
            result = subprocess.run(["bash", str(launcher / "install.sh"), "--permissions-only"],
                                    env=env, capture_output=True, text=True, timeout=60)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("is not installed", result.stderr)

    def test_install_grants_homesentinel_standby_and_usage_access(self):
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
        self.assertIn("Fire OS refused HomeSentinel's AUTO_START permission", result.stderr)

    def test_install_continues_when_auto_start_operation_is_unsupported(self):
        result, commands, state = self.install(auto_start_unsupported=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("appops get tv.dumbtv.home AUTO_START", commands)
        self.assertNotIn("appops set tv.dumbtv.home AUTO_START allow", commands)
        self.assertIn("AUTO_START is not supported by this firmware", result.stdout)
        self.assertIn("pm grant tv.dumbtv.home android.permission.READ_LOGS", commands)
        self.assertTrue(state["permissions"]["tv.dumbtv.home"]["android.permission.READ_LOGS"])
        self.assertIn("dumpsys activity services tv.dumbtv.home", commands)

    def test_install_fails_if_adb_refuses_read_logs(self):
        result, commands, state = self.install(read_logs_refused=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("pm grant tv.dumbtv.home android.permission.READ_LOGS", commands)
        self.assertIn("could not grant HomeSentinel READ_LOGS", result.stderr)
        self.assertFalse(state.get("permissions", {}).get("tv.dumbtv.home", {}).get(
            "android.permission.READ_LOGS", False))

    def test_install_fails_if_read_logs_grant_does_not_persist(self):
        result, commands, state = self.install(read_logs_not_recorded=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("pm grant tv.dumbtv.home android.permission.READ_LOGS", commands)
        self.assertIn("READ_LOGS grant was not verified", result.stderr)
        self.assertFalse(state.get("permissions", {}).get("tv.dumbtv.home", {}).get(
            "android.permission.READ_LOGS", False))


if __name__ == "__main__":
    unittest.main()
