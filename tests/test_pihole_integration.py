"""Pi-hole integration stays external to the offline DumbTV launcher."""
from pathlib import Path
import csv
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "data" / "blocklists"


def domains(path):
    found = []
    for line in path.read_text(encoding="utf-8").splitlines():
        value = line.split("#", 1)[0].strip()
        if value and not value.startswith("!"):
            found.append(value.split()[0])
    return found


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
        self.assertNotIn("dns-check.sh", all_script)
        self.assertNotIn("dns-preflight.sh", all_script)
        self.assertFalse((ROOT / "scripts" / "dns-check.sh").exists())
        for path in (ROOT / "scripts").glob("*.sh"):
            if path.name in {"cleanup-legacy-dnsfilter.sh", "restore.sh"}:
                continue
            with self.subTest(script=path.name):
                source = path.read_text()
                for obsolete in ("DNSFILTER", "SYSTEM_DNS", "FILTER_AUTOSTART",
                                 "DNS_UPSTREAM", "fallback_dns"):
                    self.assertNotIn(obsolete, source)
        self.assertNotIn("DNSFILTER", configure)
        self.assertNotIn("DNSFILTER", verify)

    def test_full_setup_runs_safe_legacy_filter_migration(self):
        all_script = (ROOT / "scripts" / "all.sh").read_text()
        migration = (ROOT / "scripts" / "cleanup-legacy-dnsfilter.sh").read_text()
        self.assertIn("cleanup-legacy-dnsfilter.sh", all_script)
        self.assertIn("if [[ $legacy_vpn == dnsfilter.android ]]", migration)
        self.assertIn("settings delete secure always_on_vpn_app", migration)
        self.assertIn("if present dnsfilter.android", migration)
        self.assertIn("appops set dnsfilter.android ACTIVATE_VPN default", migration)
        self.assertIn("appops set dnsfilter.android AUTO_START default", migration)
        self.assertIn("dumpsys deviceidle whitelist -dnsfilter.android", migration)
        self.assertIn("pm revoke dnsfilter.android android.permission.READ_EXTERNAL_STORAGE", migration)
        self.assertIn("pm disable-user --user 0 dnsfilter.android", migration)

    def test_dns_audit_is_separate_read_only_preflight(self):
        checker = (ROOT / "scripts" / "dns-preflight.sh").read_text()
        preflight = (ROOT / "scripts" / "preflight.sh").read_text()
        all_script = (ROOT / "scripts" / "all.sh").read_text()
        self.assertIn("DNS pre-installation check (read-only)", checker)
        # Resolver properties are passed individually through the dev1 helper,
        # so assert the property names rather than a literal shell command.
        for prop in ("net.dns1", "net.dns2", "dhcp.wlan0.dns1", "dhcp.wlan0.dns2"):
            self.assertIn(prop, checker)
        self.assertIn('dev1 getprop "$prop"', checker)
        self.assertIn("--pihole-ip", checker)
        self.assertIn("--unbound-ip", checker)
        self.assertIn("--no-hagezi", checker)
        self.assertIn("domain-purpose.tsv", checker)
        self.assertIn("example.com", checker)
        self.assertIn("Purpose:", checker)
        self.assertIn("dns-preflight.sh", preflight)
        syntax = subprocess.run(["bash", "-n", str(ROOT / "scripts" / "dns-preflight.sh")],
                                capture_output=True, text=True)
        self.assertEqual(syntax.returncode, 0, syntax.stderr)
        self.assertNotIn("dns-preflight.sh", all_script)
        for forbidden in ("settings put", "pm disable", "pihole -w", "pihole -g"):
            self.assertNotIn(forbidden, checker)

    def test_domain_purpose_metadata_covers_every_active_source_domain(self):
        metadata = DATA / "domain-purpose.tsv"
        with metadata.open(encoding="utf-8", newline="") as stream:
            rows = list(csv.DictReader(
                (line for line in stream if not line.startswith("#")),
                delimiter="\t",
                fieldnames=("domain", "profile", "service", "purpose", "confidence", "evidence"),
            ))
        mapped = {row["domain"]: row for row in rows}
        active = set(domains(DATA / "extra-hosts.txt"))
        active.update(domains(DATA / "alexa-hosts.txt"))
        active.update(domains(DATA / "prime-video-hosts.txt"))
        active.update(domains(DATA / "verify-allowed.txt"))
        active.update(domains(DATA / "pihole" / "prime-video-allowlist.txt"))
        active.update(domains(DATA / "hagezi-probes.txt"))
        self.assertEqual(active, set(mapped))
        for domain, row in mapped.items():
            with self.subTest(domain=domain):
                self.assertTrue(row["service"])
                self.assertTrue(row["purpose"])
                self.assertIn(row["confidence"], {"confirmed", "community", "inferred", "unknown"})
                self.assertTrue(row["evidence"])

    def test_research_candidates_are_not_active_blocklist_entries(self):
        candidates = DATA / "research-candidates.tsv"
        with candidates.open(encoding="utf-8") as stream:
            candidate_domains = {
                line.split("\t", 1)[0] for line in stream
                if line.strip() and not line.startswith("#")
            }
        active = set()
        for name in ("extra-hosts.txt", "alexa-hosts.txt", "prime-video-hosts.txt"):
            active.update(domains(DATA / name))
        self.assertTrue(candidate_domains)
        self.assertFalse(candidate_domains & active)

    def test_restore_only_clears_legacy_filter_vpn(self):
        restore = (ROOT / "scripts" / "restore.sh").read_text()
        self.assertIn("if [[ $legacy_vpn == dnsfilter.android ]]", restore)
        self.assertIn("if present dnsfilter.android", restore)
        self.assertLess(restore.index("if present dnsfilter.android"),
                        restore.index("if [[ $legacy_vpn == dnsfilter.android ]]"))


if __name__ == "__main__":
    unittest.main()
