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
    def install(self, read_logs_refused=False, read_logs_not_recorded=False, installer_args=None, foreground_line=None):
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
            shutil.copy(ROOT / "scripts/lib.sh", scripts / "lib.sh")
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
                "read_logs_refused": read_logs_refused,
                "read_logs_not_recorded": read_logs_not_recorded,
                **({"foreground_line": foreground_line} if foreground_line is not None else {}),
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

    def run_undo(self, **overrides):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            launcher = tmp / "launcher"
            scripts = tmp / "scripts"
            bindir = tmp / "bin"
            launcher.mkdir(); scripts.mkdir(); bindir.mkdir()
            shutil.copy(ROOT / "launcher/install.sh", launcher / "install.sh")
            shutil.copy(ROOT / "scripts/home.sh", scripts / "home.sh")
            shutil.copy(ROOT / "scripts/lib.sh", scripts / "lib.sh")
            (launcher / "version.env").write_text("VERSION=1.17\n")
            (bindir / "sleep").write_text("#!/bin/sh\nexit 0\n")
            (bindir / "sleep").chmod(0o755)
            state_data = {
                "packages": {"tv.dumbtv.home": {"system": False, "enabled": True}},
                "settings": {}, "sdk": 25, "homesentinel_running": True,
                "appops": {"tv.dumbtv.home": {"GET_USAGE_STATS": "allow"}},
                **overrides,
            }
            state = tmp / "state.json"; state.write_text(json.dumps(state_data))
            log = tmp / "commands.log"; log.touch()
            env = dict(os.environ, ADB=str(ROOT / "tests/fake_tv.py"), ADB_SERIAL="test-tv",
                       FAKE_TV_STATE=str(state), FAKE_TV_LOG=str(log),
                       PATH=str(bindir) + os.pathsep + os.environ["PATH"])
            result = subprocess.run(["bash", str(launcher / "install.sh"), "--undo"],
                                    env=env, capture_output=True, text=True, timeout=60)
            return result, log.read_text().splitlines(), json.loads(state.read_text())

    def test_undo_verifies_service_stop_and_amazon_home_foreground(self):
        result, commands, state = self.run_undo()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(state["settings"]["global/dumbtv_homesentinel_enabled"], "0")
        self.assertFalse(state["homesentinel_running"])
        self.assertIn("com.amazon.tv.launcher", state["foreground_line"])
        self.assertIn("HOME handed back and verified", result.stdout)

    def test_undo_does_not_claim_success_when_force_stop_fails(self):
        result, _, _ = self.run_undo(force_stop_refused=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("could not stop DumbTV", result.stderr)

    def test_undo_does_not_claim_success_when_amazon_home_cannot_be_verified(self):
        result, _, _ = self.run_undo(wrong_home_foreground=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("handoff not verified", result.stderr)

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
        grant = commands.index("pm grant tv.dumbtv.home android.permission.READ_LOGS")
        force_stop = commands.index("am force-stop tv.dumbtv.home")
        launch = commands.index("am start -W -n tv.dumbtv.home/.Home")
        self.assertLess(grant, force_stop)
        self.assertLess(force_stop, launch)

    def test_permissions_only_requires_existing_install(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            launcher = tmp / "launcher"; scripts = tmp / "scripts"; bindir = tmp / "bin"
            launcher.mkdir(); scripts.mkdir(); bindir.mkdir()
            shutil.copy(ROOT / "launcher/install.sh", launcher / "install.sh")
            shutil.copy(ROOT / "scripts/home.sh", scripts / "home.sh")
            shutil.copy(ROOT / "scripts/lib.sh", scripts / "lib.sh")
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

    def test_install_grants_homesentinel_usage_access_and_doze_exemption(self):
        result, commands, state = self.install()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("appops set tv.dumbtv.home GET_USAGE_STATS allow", commands)
        self.assertIn("dumpsys deviceidle whitelist +tv.dumbtv.home", commands)
        self.assertIn("pm grant tv.dumbtv.home android.permission.READ_LOGS", commands)
        self.assertEqual(state["appops"]["tv.dumbtv.home"]["GET_USAGE_STATS"], "allow")
        self.assertTrue(state["permissions"]["tv.dumbtv.home"]["android.permission.READ_LOGS"])
        self.assertIn("READ_LOGS granted and verified", result.stdout)

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

    def test_foreground_detection_accepts_android_7_resumed_activity(self):
        result, commands, _ = self.install()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("dumpsys activity activities", commands)
        self.assertIn("home: dumbtv", result.stdout)

    def test_foreground_detection_fails_when_activity_is_empty(self):
        result, _, _ = self.install(foreground_line="")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("could not determine the foreground activity", result.stderr)

    def test_foreground_detection_fails_when_another_package_is_in_front(self):
        result, _, _ = self.install(
            foreground_line="  mResumedActivity: ActivityRecord{1 u0 com.amazon.tv.launcher/.Launcher t1}")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("not in front", result.stderr)

if __name__ == "__main__":
    unittest.main()
