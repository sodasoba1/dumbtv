# DumbTV Control Centre — test build

This is a separate Windows desktop utility for the AFTMM DumbTV fork. It does not replace the Android launcher and does not modify Keeper or the existing scripts.

## What this test build does

- Checks the official `sodasoba1/dumbtv` `main` branch through the GitHub HTTPS API.
- Downloads the source archive for the exact returned 40-character commit.
- Extracts into a versioned folder under the current user's Local AppData.
- Rejects unsafe paths and symbolic links, and enforces archive/file-count/extracted-size limits.
- Writes an active-source manifest only after extraction and expected-file checks succeed.
- Never overwrites a pre-existing repository folder and never executes downloaded scripts.

Managed files are stored under:

`%LOCALAPPDATA%\DumbTV-ControlCentre\source-versions\<commit-sha>\`

The active source manifest is stored separately in `active-source.json`. Existing checkouts and user configuration are not touched.

## Not enabled in this first test build

ADB connection and device operations are deliberately not enabled yet. The first Rust migration foundations now include typed device identity/compatibility gates and a parser for the existing Fire OS package catalogues. The shell-to-Rust mapping and safety invariants are documented in `MIGRATION-PLAN.md`. The ADB protocol and authentication dependency still need final selection and physical AFTMM testing. This build does not bundle Google's platform-tools, Python, Bash, Git or any ADB executable.

No debloat, package, settings, launcher, Keeper, install or restore operation can be triggered from this GUI. The new Rust modules are offline foundations only; they do not connect to or modify a TV.

## Build on Windows

Install the Rust stable toolchain once from [rustup.rs](https://rustup.rs/), then open PowerShell in this directory:

```powershell
cargo build --release
.\target\release\dumbtv-control-centre.exe
```

The resulting executable is self-contained with respect to the Rust runtime; Windows system libraries remain OS dependencies. The PR's Windows Actions workflow packages the EXE, this README and the third-party notices into a portable ZIP artifact. Extract it and run the EXE; no installer, admin rights or runtime installation are required. This test executable is not code-signed, so Windows may display its normal publisher warning. Only run a build downloaded from this repository's own Actions run.

## Dependencies and licensing

The application uses eframe/egui, reqwest, serde, dirs, tempfile and zip. Their licences and the complete resolved dependency tree must be checked and recorded before a public release. This prototype does not redistribute Google's Android SDK Platform Tools or any third-party ADB executable.

The repository's MIT licence applies to this Control Centre source. Third-party dependencies remain under their own licences. This file is not legal advice or a substitute for the generated third-party notices required for distribution.

## Test checklist

1. Launch the executable on Windows 11 without installing Python, Bash, Git or ADB.
2. Check updates while online and while offline.
3. Download the latest source; confirm the commit SHA is shown.
4. Download the same commit again; confirm it does not overwrite the existing version.
5. Confirm an unrelated existing checkout and its local changes remain untouched.
6. Inspect the managed source folder and active manifest.
7. Confirm no device prompt or Fire TV change occurs.
