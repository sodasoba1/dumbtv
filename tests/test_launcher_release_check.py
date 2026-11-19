"""The remote release check verifies both the published APK hash and pinned signer."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
PIN = (ROOT / "launcher" / "signing.sha256").read_text().strip()


class LauncherReleaseCheckTest(unittest.TestCase):
    def run_remote(self, signer_digest=PIN, provide_apksigner=True):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            launcher = tmp / "launcher"
            bindir = tmp / "bin"
            launcher.mkdir()
            bindir.mkdir()
            for name in ("release-check.sh", "verify-certificate.sh", "signing.sha256"):
                shutil.copy(ROOT / "launcher" / name, launcher / name)
            (launcher / "version.env").write_text("VERSION=1.11\\n")
            apk = tmp / "remote.apk"
            apk.write_bytes(b"remote signed apk fixture")
            sidecar = tmp / "remote.apk.sha256"
            sidecar.write_text(hashlib.sha256(apk.read_bytes()).hexdigest() + "\\n")

            curl = bindir / "curl"
            curl.write_text(
                "#!/usr/bin/env bash\\n"
                "out= url=\\n"
                "while (($#)); do\\n"
                "  if [[ $1 == -o ]]; then out=$2; shift 2; else url=$1; shift; fi\\n"
                "done\\n"
                "case $url in *.sha256) cp \"$FAKE_SIDECAR\" \"$out\" ;; *) cp \"$FAKE_APK\" \"$out\" ;; esac\\n"
            )
            curl.chmod(0o755)
            sdk = tmp / "sdk"
            signer = sdk / "build-tools" / "34.0.0" / "apksigner"
            if provide_apksigner:
                signer.parent.mkdir(parents=True)
                signer.write_text(
                    "#!/usr/bin/env bash\\n"
                    '[[ $1 == verify && $2 == --print-certs ]] || exit 90\\n'
                    'printf \'Signer #1 certificate SHA-256 digest: %s\\n\' "$FAKE_SIGNER_DIGEST"\\n'
                )
                signer.chmod(0o755)
            env = dict(os.environ, ANDROID_HOME=str(sdk), FAKE_APK=str(apk),
                       FAKE_SIDECAR=str(sidecar), FAKE_SIGNER_DIGEST=signer_digest,
                       PATH=str(bindir) + os.pathsep + os.environ.get("PATH", ""))
            return subprocess.run(["bash", str(launcher / "release-check.sh"), "--remote"],
                                  env=env, capture_output=True, text=True, timeout=30)

    def test_remote_check_accepts_matching_hash_and_pinned_signer(self):
        result = self.run_remote()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("matches the trusted signer pin", result.stdout)

    def test_remote_check_rejects_hash_valid_apk_with_wrong_signer(self):
        result = self.run_remote(signer_digest="0" * 64)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("signer certificate verification failed", result.stdout)

    def test_remote_check_fails_if_apksigner_is_missing(self):
        result = self.run_remote(provide_apksigner=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("apksigner not found", result.stderr)


if __name__ == "__main__":
    unittest.main()
