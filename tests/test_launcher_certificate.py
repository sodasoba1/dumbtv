"""Release APK signer pin is checked before the installer trusts a download."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
VERIFY = ROOT / "launcher" / "verify-certificate.sh"
PIN = (ROOT / "launcher" / "release-certificate.sha256").read_text().strip()


class LauncherCertificateTest(unittest.TestCase):
    def run_verifier(self, digest, exit_code=0):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            apk = tmp / "release.apk"
            apk.write_bytes(b"fake apk")
            signer = tmp / "apksigner"
            signer.write_text(
                "#!/usr/bin/env bash\n"
                f"printf '%s\\n' 'Signer #1 certificate SHA-256 digest: {digest}'\n"
                f"exit {exit_code}\n"
            )
            signer.chmod(0o755)
            env = dict(os.environ, APKSIGNER=str(signer))
            return subprocess.run(
                ["bash", str(VERIFY), str(apk)], env=env,
                capture_output=True, text=True, timeout=30,
            )

    def test_accepts_the_pinned_release_certificate(self):
        result = self.run_verifier(PIN)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("release certificate verified", result.stdout)

    def test_rejects_a_different_signing_certificate(self):
        result = self.run_verifier("0" * 64)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("does not match the trusted DumbTV certificate", result.stderr)
        self.assertIn("not installing", result.stderr)

    def test_rejects_an_apk_that_apksigner_cannot_verify(self):
        result = self.run_verifier(PIN, exit_code=1)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("could not verify", result.stderr)


if __name__ == "__main__":
    unittest.main()
