"""Legacy VPN migration must not alter non-Fire OS Android devices."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class LegacyDnsfilterGuardTest(unittest.TestCase):
    def test_non_fire_os_device_is_rejected_before_vpn_changes(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            state = tmp / "state.json"
            state.write_text(json.dumps({
                "manufacturer": "Google",
                "sdk": 28,
                "packages": {"dnsfilter.android": {"system": False, "enabled": True}},
                "settings": {"secure/always_on_vpn_app": "dnsfilter.android"},
            }))
            log = tmp / "commands.log"
            log.touch()
            env = dict(os.environ, ADB=str(ROOT / "tests" / "fake_tv.py"),
                       ADB_SERIAL="test-tv", FAKE_TV_STATE=str(state),
                       FAKE_TV_LOG=str(log), REPORT=str(tmp / "report"))
            result = subprocess.run(
                ["bash", str(ROOT / "scripts" / "cleanup-legacy-dnsfilter.sh")],
                env=env, capture_output=True, text=True, timeout=30,
            )
            commands = log.read_text().splitlines()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("this is for Amazon Fire OS", result.stderr)
        self.assertFalse(any(command.startswith("pm disable-user")
                             or command.startswith("settings delete")
                             for command in commands))


if __name__ == "__main__":
    unittest.main()
