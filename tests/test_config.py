"""Household configuration is data, never executable shell."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ConfigSafetyTest(unittest.TestCase):
    def load(self, text):
        with tempfile.TemporaryDirectory() as tmp:
            conf = Path(tmp) / "dumbtv.conf"
            conf.write_text(text)
            env = dict(os.environ, CONF=str(conf))
            return subprocess.run(
                ["bash", "-c", 'source "$1/scripts/lib.sh"; load_conf; '
                 'printf "%s|%s|%s|%s|%s\\n" "$KEEP_APPS" "$REMOVE_EXTRA" '
                 '"$ALEXA" "$LAUNCHER" "$YOUTUBE"', "_", str(ROOT)],
                env=env, capture_output=True, text=True, timeout=10)

    def test_valid_household_configuration_is_parsed(self):
        result = self.load(
            'KEEP_APPS="com.netflix.ninja"\\nREMOVE_EXTRA=""\\n'
            'ALEXA=0\\nLAUNCHER=0\\nYOUTUBE=1\\n')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "com.netflix.ninja||0|0|1")

    def test_shell_code_in_config_is_rejected_without_execution(self):
        with tempfile.TemporaryDirectory() as tmp:
            marker = Path(tmp) / "executed"
            conf = Path(tmp) / "dumbtv.conf"
            conf.write_text('ALEXA=$(touch ' + str(marker) + ')\\n')
            env = dict(os.environ, CONF=str(conf))
            result = subprocess.run(
                ["bash", "-c", 'source "$1/scripts/lib.sh"; load_conf', "_", str(ROOT)],
                env=env, capture_output=True, text=True, timeout=10)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(marker.exists())


if __name__ == "__main__":
    unittest.main()
