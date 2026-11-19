"""Release APK signer pin is checked with apksigner before the installer trusts a download."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
VERIFY = ROOT / "launcher" / "verify-certificate.sh"
PIN = (ROOT / "launcher" / "signing.sha256").read_text().strip()


class LauncherCertificateTest(unittest.TestCase):
    def run_verifier(self, digest, exit_code=0, extra_output="", sdk_path=True):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            apk = tmp / "release.apk"
            apk.write_bytes(b"fake apk")
            sdk = tmp / "android-sdk"
            signer = sdk / "build-tools" / "34.0.0" / "apksigner"
            signer.parent.mkdir(parents=True)
            signer.write_text(
                "#!/usr/bin/env bash\n"
                '[[ "$1" == verify && "$2" == --print-certs && "$3" == "$APK_UNDER_TEST" ]] || exit 90\n'
                f"printf '%s\\n' 'Signer #1 certificate SHA-256 digest: {digest}'\n"
                f"printf '%s\\n' {extra_output!r}\n"
                f"exit {exit_code}\n"
            )
            signer.chmod(0o755)
            env = dict(os.environ, ANDROID_HOME=str(sdk) if sdk_path else "")
            env["APK_UNDER_TEST"] = str(apk)
            return subprocess.run(["bash", str(VERIFY), str(apk)], env=env,
                                  capture_output=True, text=True, timeout=30)

    def test_accepts_the_pinned_release_certificate_from_apksigner(self):
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
        self.assertIn("not installing", result.stderr)

    def test_rejects_multiple_signers(self):
        result = self.run_verifier(PIN, extra_output="Signer #2 certificate SHA-256 digest: " + PIN)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("expected exactly one APK signer", result.stderr)

    def test_uses_apksigner_from_path_when_sdk_path_is_not_available(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            apk = tmp / "release.apk"
            apk.write_bytes(b"fake apk")
            bindir = tmp / "bin"
            bindir.mkdir()
            signer = bindir / "apksigner"
            signer.write_text("#!/usr/bin/env bash\\n"
                              '[[ "$1" == verify && "$2" == --print-certs ]] || exit 90\\n'
                              f"printf '%s\\\\n' 'Signer #1 certificate SHA-256 digest: {PIN}'\\n")
            signer.chmod(0o755)
            env = dict(os.environ, ANDROID_HOME="", PATH=str(bindir) + os.pathsep + os.environ.get("PATH", ""))
            result = subprocess.run(["bash", str(VERIFY), str(apk)], env=env,
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_windows_git_bash_discovers_and_invokes_apksigner_bat_with_spaced_paths(self):
        with tempfile.TemporaryDirectory(prefix="dumbtv test ") as tmp:
            tmp = Path(tmp)
            sdk = tmp / "Android SDK"
            signer = sdk / "build-tools" / "34.0.0" / "apksigner.bat"
            signer.parent.mkdir(parents=True)
            signer.write_text("@echo off\\r\\n")
            apk = tmp / "release package.apk"
            apk.write_bytes(b"fake apk")
            bindir = tmp / "mock-bin"
            bindir.mkdir()
            capture = tmp / "cmd-arguments.txt"

            cygpath = bindir / "cygpath"
            cygpath.write_text(
                "#!/usr/bin/env bash\\n"
                '[[ "$1" == -w || "$1" == -u ]] || exit 91\\n'
                'printf "%s\\\\n" "$2"\\n'
            )
            cygpath.chmod(0o755)
            cmd = bindir / "cmd.exe"
            cmd.write_text(
                "#!/usr/bin/env bash\\n"
                'printf "%s\\\\n" "$@" > "$CMD_CAPTURE"\\n'
                f"printf '%s\\\\n' 'Signer #1 certificate SHA-256 digest: {PIN}'\\n"
            )
            cmd.chmod(0o755)

            env = dict(os.environ)
            env.update({
                "ANDROID_HOME": str(sdk),
                "PATH": str(bindir) + os.pathsep + env.get("PATH", ""),
                "CMD_CAPTURE": str(capture),
            })
            result = subprocess.run(["bash", str(VERIFY), str(apk)], env=env,
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("release certificate verified", result.stdout)
            args = capture.read_text()
            self.assertIn("verify --print-certs", args)
            self.assertIn(str(signer), args)
            self.assertIn(str(apk), args)

    def test_missing_apksigner_is_a_hard_failure(self):
        if shutil.which("apksigner"):
            self.skipTest("apksigner is installed on this test host")
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            apk = tmp / "release.apk"
            apk.write_bytes(b"fake apk")
            sdk = tmp / "empty-sdk"
            sdk.mkdir()
            env = dict(os.environ, ANDROID_HOME=str(sdk))
            result = subprocess.run(["bash", str(VERIFY), str(apk)], env=env,
                                    capture_output=True, text=True, timeout=30)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("install Android SDK build-tools 34.0.0", result.stderr)


if __name__ == "__main__":
    unittest.main()
