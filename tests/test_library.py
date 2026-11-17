"""scripts/library.sh deploys assets only to the AFTMM API 25 app-specific files directory."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
DEST = "/sdcard/Android/data/tv.dumbtv.home/files/library"


def fake_adb(path):
    path.write_text(
        "#!/usr/bin/env python3\n"
        "import os, sys\n"
        "args = sys.argv[1:]\n"
        "with open(os.environ['ADB_LOG'], 'a') as f: f.write(' '.join(args) + '\\n')\n"
        "if args[-3:] == ['shell', 'getprop', 'ro.build.version.sdk']:\n"
        " print(os.environ.get('FAKE_SDK', '25'))\n"
    )
    path.chmod(0o755)


class LibraryDeployTest(unittest.TestCase):
    def run_script(self, sdk):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        tmp = Path(tmp.name)
        source = tmp / "library"
        source.mkdir()
        (source / "library.conf").write_text("# no titles for this path test\n")
        log = tmp / "adb.log"
        adb = tmp / "adb"
        fake_adb(adb)
        env = dict(os.environ, ADB=str(adb), ADB_SERIAL="test-tv",
                   ADB_LOG=str(log), FAKE_SDK=str(sdk))
        result = subprocess.run(
            ["bash", str(ROOT / "scripts" / "library.sh"), str(source)],
            env=env, capture_output=True, text=True, timeout=30,
        )
        commands = log.read_text().splitlines() if log.exists() else []
        return result, commands, source

    def test_library_uses_the_api25_external_files_path(self):
        result, commands, source = self.run_script(25)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(f"-s test-tv shell rm -rf '{DEST}' && mkdir -p '{DEST}'", commands)
        self.assertTrue(any(line.startswith("-s test-tv push ") and
                            line.endswith(f"/library.conf {DEST}/") for line in commands), commands)

    def test_rejects_non_api25_devices_before_push(self):
        result, commands, _ = self.run_script(30)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("only the AFTMM launcher target (API 25)", result.stderr)
        self.assertFalse(any(" push " in line for line in commands))


if __name__ == "__main__":
    unittest.main()
