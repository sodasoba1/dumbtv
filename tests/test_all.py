"""Test orchestration using stub steps, without ADB, downloads or a television.

Run: python3 -m unittest discover -s tests -v
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
STEPS = ["preflight", "cleanup-legacy-dnsfilter", "debloat", "lockdown", "launcher"]


class AllTest(unittest.TestCase):
    def run_all(self, config=None, answer="0", flags=(), inherited_youtube=None, conf_env=None, sdk="25"):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp) / "repo with spaces"
            scripts = repo / "scripts"
            scripts.mkdir(parents=True)
            shutil.copy2(ROOT / "scripts" / "all.sh", scripts / "all.sh")
            shutil.copy2(ROOT / "scripts" / "lib.sh", scripts / "lib.sh")
            adb = tmp / "adb"
            adb.write_text("""#!/usr/bin/env bash
if [[ $1 == start-server ]]; then exit 0; fi
if [[ $1 == -s && $2 == test-tv && $3 == shell ]]; then
  shift 3
  case "$*" in
    "getprop ro.build.version.sdk") echo "${FAKE_SDK:-25}" ;;
    "cmd package help") echo "install-existing" ;;
    *) echo "unexpected adb command: $*" >&2; exit 92 ;;
  esac
  exit 0
fi
echo "unexpected adb invocation: $*" >&2
exit 91
""")
            adb.chmod(0o755)
            if config is not None:
                (repo / "dumbtv.conf").write_text(config)
            for name in STEPS + ["configure", "youtube", "verify"]:
                stub = scripts / (name + ".sh")
                stub.write_text('''#!/usr/bin/env bash
set -eu
name=$(basename "$0" .sh)
printf '%s' "$name" >> "$STEP_LOG"
printf ' %s' "$@" >> "$STEP_LOG"
if [[ $name == youtube ]]; then printf ' ASSUME_YES=%s' "${ASSUME_YES:-0}" >> "$STEP_LOG"; fi
printf '\\n' >> "$STEP_LOG"
if [[ $name == configure ]]; then
  for arg in "$@"; do [[ $arg != --dry-run ]] || exit 0; done
  printf 'YOUTUBE=%s\\n' "$CONFIG_ANSWER" > "$(dirname "$0")/../dumbtv.conf"
fi
''')
                stub.chmod(0o755)
            log = repo / "steps.log"
            env = dict(os.environ, STEP_LOG=str(log), CONFIG_ANSWER=answer,
                       ADB=str(adb), ADB_SERIAL="test-tv", FAKE_SDK=str(sdk))
            env.pop("YOUTUBE", None)
            env.pop("CONF", None)
            if conf_env is not None:
                conf = Path(tmp) / "elsewhere.conf"
                conf.write_text(conf_env)
                env["CONF"] = str(conf)
            if inherited_youtube is not None:
                env["YOUTUBE"] = inherited_youtube
            result = subprocess.run(
                ["bash", str(scripts / "all.sh"), *flags], env=env, cwd=repo,
                capture_output=True, text=True,
            )
            self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
            self.last_stdout = result.stdout
            return [line.split() for line in log.read_text().splitlines()]

    def test_saved_yes_runs_youtube_before_verification(self):
        steps = self.run_all(config="YOUTUBE=1\n")
        self.assertEqual(steps, [[name] for name in STEPS] + [["youtube", "ASSUME_YES=1"], ["verify"]])

    def test_saved_no_overrides_inherited_yes(self):
        steps = self.run_all(config="YOUTUBE=0\n", inherited_youtube="1")
        self.assertEqual(steps, [[name] for name in STEPS + ["verify"]])

    def test_conf_from_the_environment_is_the_one_read(self):
        steps = self.run_all(conf_env="YOUTUBE=1\n")
        self.assertEqual(steps, [[name] for name in STEPS] + [["youtube", "ASSUME_YES=1"], ["verify"]])

    def test_older_config_defaults_to_no(self):
        steps = self.run_all(config="PRIME=1\n")
        self.assertEqual(steps, [[name] for name in STEPS + ["verify"]])

    def test_new_yes_is_loaded_after_configure(self):
        steps = self.run_all(answer="1")
        expected = ["preflight", "configure", *STEPS[1:], "youtube", "verify"]
        self.assertEqual(steps, [[name] for name in expected[:-2]] + [["youtube", "ASSUME_YES=1"], ["verify"]])

    def test_new_no_skips_youtube(self):
        steps = self.run_all(answer="0")
        expected = ["preflight", "configure", *STEPS[1:], "verify"]
        self.assertEqual(steps, [[name] for name in expected])

    def test_non_api25_device_skips_launcher_but_completes_cleanup(self):
        steps = self.run_all(config="LAUNCHER=1\n", sdk="28")
        self.assertEqual(steps, [[name] for name in STEPS if name != "launcher"] + [["verify"]])
        self.assertIn("skip launcher", self.last_stdout)
        self.assertIn("API 25 only", self.last_stdout)

    def test_dry_run_passes_flags_to_opted_in_step(self):
        steps = self.run_all(config="YOUTUBE=1\n", flags=("--dry-run", "-y"))
        expected = [[name, "--dry-run", "-y"] for name in STEPS]
        self.assertEqual(steps, expected + [["youtube", "--dry-run", "-y", "ASSUME_YES=1"], ["verify"]])

    def test_dry_run_without_config_does_not_require_a_written_file(self):
        steps = self.run_all(answer="1", flags=("--dry-run",))
        expected = [[name, "--dry-run"] for name in ["preflight", "configure", *STEPS[1:]]]
        self.assertEqual(steps, expected + [["verify"]])


if __name__ == "__main__":
    unittest.main()
