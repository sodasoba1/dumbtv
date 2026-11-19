"""discover.sh dry-run previews the inventory without creating report files."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


def fake_adb(path):
    path.write_text("""#!/usr/bin/env python3
import sys
args = sys.argv[1:]
if args == ["start-server"]:
    raise SystemExit(0)
if args[:1] == ["-s"] and args[2:3] == ["shell"]:
    command = " ".join(args[3:])
    replies = {
        "getprop ro.build.version.sdk": "25",
        "cmd package help": "Package manager commands: path [--user USER_ID] PACKAGE",
        "getprop ro.product.manufacturer": "Amazon",
        "getprop ro.product.model": "AFTMM",
        "getprop ro.build.version.release": "7.1.2",
        "pm list packages -f": "package:/system/app/Analytics/base.apk=com.vendor.analytics",
        "pm list packages -d": "",
        "pm list packages -3": "",
    }
    if command not in replies:
        print("unexpected command: " + command, file=sys.stderr)
        raise SystemExit(92)
    print(replies[command])
    raise SystemExit(0)
raise SystemExit(91)
""")
    path.chmod(0o755)


class DiscoverDryRunTest(unittest.TestCase):
    def run_discover(self, dry_run):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        tmp = Path(tmp.name)
        adb = tmp / "adb"
        fake_adb(adb)
        report = tmp / "report"
        env = dict(os.environ, ADB=str(adb), ADB_SERIAL="test-tv", REPORT=str(report))
        args = ["bash", str(ROOT / "scripts" / "discover.sh")]
        if dry_run:
            args.append("--dry-run")
        result = subprocess.run(args, env=env, capture_output=True, text=True, timeout=30)
        return result, report

    def test_dry_run_prints_report_without_creating_report_directory(self):
        result, report = self.run_discover(True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("dry-run: report not written", result.stderr)
        self.assertIn("likely ads / telemetry / promo", result.stdout)
        self.assertIn("com.vendor.analytics", result.stdout)
        self.assertFalse(report.exists())

    def test_normal_run_writes_the_report(self):
        result, report = self.run_discover(False)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        files = list(report.glob("discover-fireos-*.txt"))
        self.assertEqual(len(files), 1)
        self.assertIn("com.vendor.analytics", files[0].read_text())


if __name__ == "__main__":
    unittest.main()
