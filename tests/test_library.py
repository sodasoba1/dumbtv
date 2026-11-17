"""scripts/library.sh deploys assets to the AFTMM API 25 app-specific files directory."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
DEST = "/sdcard/Android/data/tv.dumbtv.home/files/library"


class LibraryDeployTest(unittest.TestCase):
    def test_library_uses_the_api25_external_files_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            source = tmp / "library"
            source.mkdir()
            (source / "library.conf").write_text("# no titles for this path test\n")
            log = tmp / "adb.log"
            adb = tmp / "adb"
            adb.write_text(
                "#!/usr/bin/env python3\n"
                "import os, sys\n"
                "with open(os.environ['ADB_LOG'], 'a') as f: f.write(' '.join(sys.argv[1:]) + '\\n')\n"
            )
            adb.chmod(0o755)
            env = dict(os.environ, ADB=str(adb), ADB_SERIAL="test-tv", ADB_LOG=str(log))
            result = subprocess.run(
                ["bash", str(ROOT / "scripts" / "library.sh"), str(source)],
                env=env, capture_output=True, text=True, timeout=30,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            commands = log.read_text().splitlines()
            self.assertIn(f"-s test-tv shell rm -rf '{DEST}' && mkdir -p '{DEST}'", commands)
            self.assertTrue(any(line.startswith("-s test-tv push ") and line.endswith(f"/library.conf {DEST}/") for line in commands), commands)
            self.assertFalse(any("getprop ro.build.version.sdk" in line for line in commands))


if __name__ == "__main__":
    unittest.main()
