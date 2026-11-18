"""scripts/library.sh stages uploads before replacing the existing TV Library."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
DEST = "/sdcard/Android/data/tv.dumbtv.home/files/library"


def fake_adb(path):
    path.write_text(r"""#!/usr/bin/env python3
import os, sys
args = sys.argv[1:]
with open(os.environ['ADB_LOG'], 'a') as f:
    f.write(' '.join(args) + '\n')
if args[-3:] == ['shell', 'getprop', 'ro.build.version.sdk']:
    print(os.environ.get('FAKE_SDK', '25'))
elif len(args) >= 3 and args[2] == 'push' and os.environ.get('FAKE_PUSH_FAIL') == '1':
    sys.exit(1)
""")
    path.chmod(0o755)


class LibraryDeployTest(unittest.TestCase):
    def run_script(self, sdk=25, image_line=None, push_fail=False):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        tmp = Path(tmp.name)
        source = tmp / "library"
        source.mkdir()
        line = "# no titles for this path test\n" if image_line is None else image_line
        (source / "library.conf").write_text(line)
        (tmp / "victim.jpg").write_bytes(b"not an image")
        log = tmp / "adb.log"
        adb = tmp / "adb"
        fake_adb(adb)
        env = dict(os.environ, ADB=str(adb), ADB_SERIAL="test-tv",
                   ADB_LOG=str(log), FAKE_SDK=str(sdk),
                   FAKE_PUSH_FAIL="1" if push_fail else "0")
        result = subprocess.run(
            ["bash", str(ROOT / "scripts" / "library.sh"), str(source)],
            env=env, capture_output=True, text=True, timeout=30,
        )
        commands = log.read_text().splitlines() if log.exists() else []
        return result, commands, source

    def test_library_uploads_to_staging_then_swaps(self):
        result, commands, _ = self.run_script()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        pushes = [line for line in commands if " push " in line]
        self.assertEqual(len(pushes), 1, commands)
        self.assertIn(DEST + ".staging.", pushes[0])
        stage_push = commands.index(pushes[0])
        swap = next(i for i, line in enumerate(commands)
                    if " shell if [ -d " in line and "mv" in line)
        self.assertLess(stage_push, swap)
        self.assertTrue(any("mkdir -p" in line and ".staging." in line for line in commands))

    def test_failed_push_leaves_existing_library_in_place(self):
        result, commands, _ = self.run_script(push_fail=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("existing Library was left untouched", result.stderr)
        self.assertFalse(any("mv '" + DEST + "'" in line for line in commands), commands)

    def test_rejects_picture_path_traversal_before_push(self):
        result, commands, _ = self.run_script(image_line="../victim.jpg | title | pkg\n")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("picture names must be simple file names", result.stderr)
        self.assertFalse(any(" push " in line for line in commands), commands)

    def test_rejects_non_api25_devices_before_push(self):
        result, commands, _ = self.run_script(sdk=30)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("only the AFTMM launcher target (API 25)", result.stderr)
        self.assertFalse(any(" push " in line for line in commands))


if __name__ == "__main__":
    unittest.main()
