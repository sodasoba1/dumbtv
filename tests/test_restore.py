"""restore.sh against a pretend Fire TV whose adb session drops when the DNS
filter's VPN stops, as it does on a real TV over Wi-Fi.

Run: python3 -m unittest discover -s tests -v
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class RestoreTest(unittest.TestCase):
    def run_restore(self, ignores_reboot=False, settings=None, svc_refused=False,
                    package_snapshot=None, manufacturer='Amazon', protected_disabled=False,
                    protected_state=3, manifest_serial="test-tv:5555",
                    secure_snapshot=None, state_overrides=None, lockdown_snapshot=None,
                    data_saver_snapshot=None, global_snapshot=None, completed_restore=False,
                    allow_legacy=False, lockdown_state=None):
        packages = {
            "dnsfilter.android": {"system": False, "enabled": True},
            "com.amazon.hedwig": {"system": True, "enabled": False},
        }
        if protected_disabled:
            packages["com.amazon.firebat"] = {
                "system": True, "enabled": False, "enabled_state": protected_state
            }
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            state = tmp / "state.json"
            hooked = {
                "secure/always_on_vpn_app": "dnsfilter.android",
                **(settings or {}),
            }
            state.write_text(json.dumps({"packages": packages, "settings": hooked,
                                         "manufacturer": manufacturer,
                                         "props": {"ro.product.model": "AFTMM",
                                                   "ro.build.display.id": "test-build",
                                                   "sys.boot_completed": "1"},
                                         "ignores_reboot": ignores_reboot,
                                         "svc_refused": svc_refused, "sdk": 25,
                                         "omit_disabled_package": "com.amazon.firebat" if protected_disabled else "",
                                         **(state_overrides or {})}))
            log = tmp / "commands.log"
            log.touch()
            (tmp / "dumbtv.conf").write_text("")
            report = tmp / "report"
            report.mkdir()
            (report / "device.txt").write_text(
                "format=2\nserial_sha256=" + hashlib.sha256(manifest_serial.encode()).hexdigest()
                + "\nmodel=AFTMM\nbuild=test-build\n")
            if package_snapshot is not None:
                (report / "packages-before.txt").write_text(package_snapshot)
            if completed_restore:
                (report / "restore-complete.txt").write_text("format=1\n")
            if secure_snapshot is not None:
                (report / "settings-before-secure.txt").write_text(secure_snapshot)
            if global_snapshot is not None:
                (report / "settings-before-global.txt").write_text(global_snapshot)
            if lockdown_snapshot is not None:
                (report / "lockdown-before.tsv").write_text(lockdown_snapshot)
            if data_saver_snapshot is not None:
                (report / "data-saver-before.txt").write_text(data_saver_snapshot)
            if lockdown_state is not None:
                (report / "lockdown-state.txt").write_text(lockdown_state)
            env = dict(os.environ, ADB=str(ROOT / "tests" / "fake_tv.py"), ADB_SERIAL="test-tv:5555",
                       FAKE_TV_STATE=str(state), FAKE_TV_LOG=str(log),
                       CONF=str(tmp / "dumbtv.conf"), REPORT=str(report), BOOT_SECONDS="0",
                       PM_WRITE_SECONDS="0",
                       DUMBTW_ALLOW_LEGACY_REPORT="1" if allow_legacy else "0")
            result = subprocess.run(["bash", str(ROOT / "scripts" / "restore.sh"), "-y"],
                                    env=env, capture_output=True, text=True, timeout=120)
            self.package_snapshot_exists_after = (report / "packages-before.txt").exists()
            self.restore_complete_exists_after = (report / "restore-complete.txt").exists()
            self.secure_snapshot_exists_after = (report / "settings-before-secure.txt").exists()
            return result, log.read_text().splitlines(), json.loads(state.read_text())

    def test_every_change_reaches_the_tv_before_the_reboot(self):
        result, commands, state = self.run_restore()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        stop_filter = commands.index("pm disable-user --user 0 dnsfilter.android")
        settings = [i for i, c in enumerate(commands) if c.startswith("settings put")]
        self.assertTrue(settings)
        self.assertLess(max(settings), stop_filter)
        self.assertIn("svc power reboot", commands[stop_filter:])
        self.assertNotIn("reboot", commands)
        self.assertEqual(state["boots"], 1)
        self.assertTrue(state["packages"]["com.amazon.hedwig"]["enabled"])
        self.assertEqual(state.get("bad", []), [])

    def test_successful_restore_marks_the_report_complete(self):
        result, _, _ = self.run_restore()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue(self.restore_complete_exists_after)

    def test_restore_does_not_reapply_after_the_baseline_has_been_cleared(self):
        result, commands, _ = self.run_restore(completed_restore=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("restore already completed", result.stderr)
        self.assertFalse(any(command.startswith("pm enable") or command.startswith("settings put")
                             for command in commands))

    def test_restore_preserves_packages_disabled_before_dumbtv(self):
        result, commands, state = self.run_restore(
            package_snapshot="com.amazon.hedwig\tdisabled\n")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertFalse(state["packages"]["com.amazon.hedwig"]["enabled"])
        self.assertNotIn("pm enable --user 0 com.amazon.hedwig", commands)
        self.assertIn("left disabled (was not enabled in the original snapshot)", result.stdout)
        self.assertFalse(self.package_snapshot_exists_after,
                         "successful restore clears snapshots for the next cleanup cycle")

    def test_restore_recovers_protected_package_when_disabled_listing_omits_it(self):
        result, commands, state = self.run_restore(
            protected_disabled=True,
            package_snapshot="com.amazon.firebat\tenabled\n")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue(state["packages"]["com.amazon.firebat"]["enabled"])
        self.assertIn("pm enable --user 0 com.amazon.firebat", commands)

    def test_restore_leaves_system_disabled_protected_package_untouched(self):
        result, commands, state = self.run_restore(
            protected_disabled=True, protected_state=2,
            package_snapshot="com.amazon.firebat\tenabled\n")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertFalse(state["packages"]["com.amazon.firebat"]["enabled"])
        self.assertNotIn("pm enable --user 0 com.amazon.firebat", commands)
        self.assertIn("left disabled by Fire OS (state 2)", result.stdout)

    def test_restore_refuses_new_report_without_package_baseline(self):
        result, commands, _ = self.run_restore(
            secure_snapshot="screen_timeout=300\n",
            global_snapshot="animator_duration_scale=1.0\n")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("original package baseline is missing", result.stderr)
        self.assertFalse(any(command.startswith("pm enable") for command in commands))

    def test_restore_refuses_an_empty_package_baseline(self):
        result, commands, _ = self.run_restore(package_snapshot="")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("invalid or empty original package baseline", result.stderr)
        self.assertFalse(any(command.startswith("pm enable") for command in commands))

    def test_restore_refuses_a_partial_settings_snapshot(self):
        result, commands, _ = self.run_restore(
            package_snapshot="com.amazon.hedwig\tdisabled\n",
            secure_snapshot="screen_timeout=300\n")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("both original settings files", result.stderr)
        self.assertFalse(any(command.startswith("settings put") for command in commands))

    def test_restore_refuses_a_partial_lockdown_snapshot(self):
        result, commands, _ = self.run_restore(
            package_snapshot="com.amazon.hedwig\tdisabled\n",
            lockdown_snapshot="# dumbtv-lockdown-v1\ncom.amazon.firebat\t10050\tignore\tnot-applicable\t0\n")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("original AppOps and Data Saver state must both be present", result.stderr)
        self.assertFalse(any(command.startswith("appops set") for command in commands))

    def test_restore_refuses_a_report_from_another_device(self):
        result, commands, _ = self.run_restore(manifest_serial="different-tv")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("different ADB device", result.stderr)
        self.assertFalse(any(command.startswith("settings put") or command.startswith("pm enable")
                             for command in commands))

    def test_failed_settings_write_keeps_the_original_snapshot(self):
        result, _, _ = self.run_restore(
            package_snapshot="com.amazon.hedwig\tdisabled\n",
            secure_snapshot="amz_limit_ad_tracking=1\n",
            global_snapshot="animator_duration_scale=1.0\n",
            state_overrides={"settings_write_refused_key": "secure/amz_limit_ad_tracking"})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("could not restore setting secure/amz_limit_ad_tracking", result.stderr)
        self.assertTrue(self.secure_snapshot_exists_after)

    def test_successful_settings_command_without_a_value_change_is_rejected(self):
        result, _, _ = self.run_restore(
            package_snapshot="com.amazon.hedwig\tdisabled\n",
            secure_snapshot="amz_limit_ad_tracking=1\n",
            global_snapshot="animator_duration_scale=1.0\n",
            state_overrides={"settings_put_noop_key": "secure/amz_limit_ad_tracking"})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("did not restore", result.stderr)
        self.assertTrue(self.secure_snapshot_exists_after)

    def test_restore_leaves_policy_untouched_when_lockdown_never_ran(self):
        result, commands, state = self.run_restore(
            package_snapshot="com.amazon.hedwig\tenabled\ncom.amazon.firebat\tenabled\n",
            secure_snapshot="amz_limit_ad_tracking=1\n",
            global_snapshot="animator_duration_scale=1.0\n",
            lockdown_snapshot="# dumbtv-lockdown-v1\ncom.amazon.firebat\t10050\tforeground\tnot-applicable\t0\n",
            data_saver_snapshot="disabled\n",
            allow_legacy=False,
            state_overrides={
                "packages": {
                    "dnsfilter.android": {"system": False, "enabled": True},
                    "com.amazon.hedwig": {"system": True, "enabled": True},
                    "com.amazon.firebat": {"system": True, "enabled": True, "enabled_state": 0},
                },
                "appops": {"com.amazon.firebat": {"RUN_IN_BACKGROUND": "ignore"}},
                "network_blacklist": [10050],
                "restrict_background": "enabled",
            })
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(state["appops"]["com.amazon.firebat"]["RUN_IN_BACKGROUND"], "ignore")
        self.assertIn(10050, state["network_blacklist"])
        self.assertEqual(state["restrict_background"], "enabled")
        self.assertFalse(any(command.startswith("appops set com.amazon.firebat") for command in commands))
        self.assertFalse(any(command.startswith("cmd netpolicy remove restrict-background-blacklist")
                             for command in commands))
        self.assertFalse(any(command.startswith("cmd netpolicy set restrict-background")
                             for command in commands))

    def test_restore_preserves_preexisting_appops_blacklist_and_data_saver(self):
        result, commands, state = self.run_restore(
            protected_disabled=True,
            package_snapshot="com.amazon.hedwig\tdisabled\ncom.amazon.firebat\tenabled\n",
            lockdown_snapshot="# dumbtv-lockdown-v1\ncom.amazon.firebat\t10050\tignore\tnot-applicable\t1\n",
            data_saver_snapshot="enabled\n",
            lockdown_state="complete\n",
            state_overrides={
                "appops": {"com.amazon.firebat": {"RUN_IN_BACKGROUND": "ignore"}},
                "network_blacklist": [10050],
                "restrict_background": "disabled",
            })
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(state["appops"]["com.amazon.firebat"]["RUN_IN_BACKGROUND"], "ignore")
        self.assertIn(10050, state["network_blacklist"])
        self.assertEqual(state["restrict_background"], "enabled")
        self.assertIn("cmd netpolicy set restrict-background true", commands)
        self.assertNotIn("cmd netpolicy remove restrict-background-blacklist 10050", commands)

    def test_restore_removes_only_its_own_appops_and_network_restrictions(self):
        result, commands, state = self.run_restore(
            protected_disabled=True,
            package_snapshot="com.amazon.hedwig\tdisabled\ncom.amazon.firebat\tenabled\n",
            lockdown_snapshot="# dumbtv-lockdown-v1\ncom.amazon.firebat\t10050\tallow\tnot-applicable\t0\n",
            data_saver_snapshot="disabled\n",
            lockdown_state="complete\n",
            state_overrides={
                "appops": {"com.amazon.firebat": {"RUN_IN_BACKGROUND": "ignore"}},
                "network_blacklist": [10050],
                "restrict_background": "enabled",
            })
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(state["appops"]["com.amazon.firebat"]["RUN_IN_BACKGROUND"], "allow")
        self.assertNotIn(10050, state["network_blacklist"])
        self.assertEqual(state["restrict_background"], "disabled")
        self.assertIn("cmd netpolicy set restrict-background false", commands)
        self.assertIn("cmd netpolicy remove restrict-background-blacklist 10050", commands)


    def test_restore_accepts_empty_policy_snapshot_and_restores_data_saver(self):
        result, commands, state = self.run_restore(
            package_snapshot="com.amazon.hedwig\tenabled\n",
            lockdown_snapshot="# dumbtv-lockdown-v1\n",
            data_saver_snapshot="disabled\n",
            lockdown_state="started\n")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(state["restrict_background"], "disabled")
        self.assertIn("cmd netpolicy set restrict-background false", commands)
        self.assertFalse(any(command.startswith("appops set ") for command in commands))
        self.assertFalse(any(command.startswith("cmd netpolicy remove restrict-background-blacklist")
                             for command in commands))


    def test_restore_refuses_malformed_policy_snapshot_before_policy_changes(self):
        result, commands, _ = self.run_restore(
            package_snapshot="com.amazon.hedwig\\tenabled\\n",
            lockdown_snapshot="# dumbtv-lockdown-v1\\nnot-a-valid-row\\n",
            data_saver_snapshot="disabled\\n",
            lockdown_state="started\\n")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("invalid original AppOps and network-policy snapshot", result.stderr)
        self.assertFalse(any(command.startswith("appops set ") or
                             command.startswith("cmd netpolicy remove restrict-background-blacklist")
                             for command in commands))

    def test_restore_refuses_to_run_on_non_fire_os_devices(self):
        result, commands, _ = self.run_restore(manufacturer="Google")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("this is for Amazon Fire OS", result.stderr)
        self.assertFalse(any(command.startswith("pm enable") or command.startswith("settings put")
                             for command in commands))

    def test_amazons_screensaver_comes_back(self):
        amazon = "com.amazon.ftv.screensaver/.app.services.ScreensaverService"
        result, _, state = self.run_restore(settings={
            "secure/screensaver_components": "com.android.dreams.basic/.Colors",
            "secure/screensaver_default_component": amazon,
        })
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(state["settings"]["secure/screensaver_components"], amazon)

    def test_a_screensaver_dumbtv_did_not_set_is_kept(self):
        mine = "com.example.dream/.Photos"
        result, _, state = self.run_restore(settings={
            "secure/screensaver_components": mine,
            "secure/screensaver_default_component": "com.amazon.ftv.screensaver/.S",
        })
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(state["settings"]["secure/screensaver_components"], mine)

    def test_home_and_dns_are_handed_back(self):
        result, _, state = self.run_restore()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        settings = state["settings"]
        self.assertNotIn("secure/always_on_vpn_app", settings)
        self.assertFalse(state["packages"]["dnsfilter.android"]["enabled"])
        self.assertEqual(state["settings"]["global/dumbtv_homesentinel_enabled"], "0")

    def test_a_refused_svc_reboot_falls_back_to_reboot_at_once(self):
        result, commands, state = self.run_restore(svc_refused=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        svc = commands.index("svc power reboot")
        self.assertEqual(commands[svc + 1:].count("reboot"), 1)
        self.assertEqual(state["boots"], 1)
        self.assertNotIn("asking again", result.stdout + result.stderr)

    def test_a_reboot_that_never_happened_is_not_reported_as_one(self):
        result, commands, _ = self.run_restore(ignores_reboot=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(commands.count("svc power reboot"), 1)
        self.assertEqual(commands.count("reboot"), 1)
        self.assertIn("did not restart", result.stdout + result.stderr)
        self.assertNotIn("booted", result.stdout)


if __name__ == "__main__":
    unittest.main()
