"""The launcher remains offline and the old on-device DNS filter is gone."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class PiHoleIntegrationTest(unittest.TestCase):
    def test_launcher_has_no_internet_permission_or_vpn_service(self):
        manifest = (ROOT / "launcher" / "AndroidManifest.xml").read_text()
        self.assertNotIn("android.permission.INTERNET", manifest)
        self.assertNotIn("VpnService", manifest)
        self.assertIn("android.permission.RECEIVE_BOOT_COMPLETED", manifest)

    def test_old_filter_implementation_is_absent(self):
        self.assertFalse((ROOT / "launcher/src/tv/dumbtv/home/Filter.java").exists())
        self.assertFalse((ROOT / "scripts/dnsfilter.sh").exists())
        self.assertFalse((ROOT / "tests/test_dnsfilter.py").exists())

    def test_launcher_sources_do_not_call_filter_hooks(self):
        source = ROOT / "launcher" / "src" / "tv" / "dumbtv" / "home"
        for name in ("Home.java", "Guard.java", "BootReceiver.java"):
            with self.subTest(source=name):
                text = (source / name).read_text()
                self.assertNotIn("Filter.", text)

    def test_normal_workflow_does_not_install_or_verify_dns_filter(self):
        all_script = (ROOT / "scripts" / "all.sh").read_text()
        configure = (ROOT / "scripts" / "configure.sh").read_text()
        verify = (ROOT / "scripts" / "verify.sh").read_text()
        self.assertNotIn('"$DIR/dnsfilter.sh"', all_script)
        self.assertNotIn("DNSFILTER", configure)
        self.assertNotIn("DNSFILTER", verify)

    def test_full_setup_runs_safe_legacy_filter_migration(self):
        all_script = (ROOT / "scripts" / "all.sh").read_text()
        migration = (ROOT / "scripts" / "cleanup-legacy-dnsfilter.sh").read_text()
        self.assertIn('cleanup-legacy-dnsfilter.sh', all_script)
        self.assertIn("if [[ $legacy_vpn == dnsfilter.android ]]", migration)
        self.assertIn("settings delete secure always_on_vpn_app", migration)
        self.assertIn("if present dnsfilter.android", migration)
        self.assertIn("pm disable-user --user 0 dnsfilter.android", migration)

    def test_launcher_upgrade_retires_only_the_legacy_filter_vpn(self):
        installer = (ROOT / "launcher" / "install.sh").read_text()
        self.assertIn("if [[ $legacy_vpn == dnsfilter.android ]]", installer)
        self.assertIn("settings delete secure always_on_vpn_app", installer)
        self.assertIn("if installed dnsfilter.android", installer)
        self.assertIn("pm disable-user --user 0 dnsfilter.android", installer)
        self.assertGreater(installer.index("if installed dnsfilter.android"),
                           installer.index("printf 'front window:"))

    def test_restore_only_clears_legacy_filter_vpn(self):
        restore = (ROOT / "scripts" / "restore.sh").read_text()
        self.assertIn("if [[ $legacy_vpn == dnsfilter.android ]]", restore)
        self.assertIn("if present dnsfilter.android", restore)
        self.assertIn("disturb another VPN app", restore)


if __name__ == "__main__":
    unittest.main()
