"""Configuration accepts only launcher/debloat choices; DNS filtering is external."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ConfigureTest(unittest.TestCase):
    def configure(self, *flags):
        with tempfile.TemporaryDirectory() as tmp:
            conf = Path(tmp) / "dumbtv.conf"
            result = subprocess.run(
                ["bash", str(ROOT / "scripts" / "configure.sh"), "-y", *flags],
                env=dict(os.environ, CONF=str(conf)), capture_output=True, text=True, timeout=30,
            )
            return result, conf.read_text() if conf.exists() else None

    def test_defaults_keep_alexa_and_change_no_apps(self):
        result, conf = self.configure()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('REMOVE_EXTRA=""', conf)
        self.assertIn("ALEXA=1", conf)
        self.assertIn("LAUNCHER=1", conf)
        self.assertIn("YOUTUBE=0", conf)
        self.assertNotIn("DNSFILTER", conf)
        self.assertNotIn("SYSTEM_DNS", conf)
        self.assertNotIn("PRIME=", conf)

    def test_supported_flags_are_saved(self):
        result, conf = self.configure("--alexa", "0", "--launcher", "0", "--youtube", "1")
        self.assertEqual(result.returncode, 0, result.stderr)
        for line in ("ALEXA=0", "LAUNCHER=0", "YOUTUBE=1"):
            self.assertIn(line, conf)

    def test_removed_dns_flags_are_rejected(self):
        for flag, value in (("--dnsfilter", "1"), ("--system-dns", "1"),
                            ("--dns-upstream", "1"), ("--prime", "0")):
            with self.subTest(flag=flag):
                result, conf = self.configure(flag, value)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("unknown flag", result.stderr)
                self.assertIsNone(conf)

    def test_a_yes_no_flag_takes_only_0_or_1(self):
        result, conf = self.configure("--youtube", "yes")
        self.assertNotEqual(result.returncode, 0)
        self.assertIsNone(conf)

    def test_keep_list_still_validates_packages(self):
        result, conf = self.configure("--keep", "com.amazon.tv.acr")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('KEEP_APPS="com.amazon.tv.acr"', conf)


if __name__ == "__main__":
    unittest.main()
