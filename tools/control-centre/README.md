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

## Available in this test build

- Prepare a dedicated 2048-bit ADB host key in the current Windows user's CNG key store. The private key is not exported.
- Display the Android 7-era MD5 fingerprint (for comparison with the Fire TV's authorisation prompt) and a separate SHA-256 fingerprint.
- Connect to one owner-entered private/local IP address on TCP 5555 using Rust `libadb` and the Windows CNG authenticator.
- Read the device's `getprop` output first; only if it identifies a supported Amazon Fire OS SDK 25–30 device, read package-manager inventories with bounded response buffers. Show model, build, Android version, SDK, ABI, cleanup/launcher compatibility and installed/system/user/disabled package counts.
- Restrict the connection target to private, loopback or link-local addresses.

On a first connection, the Fire TV may ask to authorise this PC's ADB public key. Compare the MD5 fingerprint shown in the app with the TV prompt before allowing it. The TV adds the key to its trusted ADB host list only if you approve. Selecting "Always allow" on the TV saves that authorisation for later connections.

**This ADB path has not yet been physically tested on the AFTMM.** The selected `libadb` release documents Android 9+ as its supported range; the AFTMM is Android 7.1.2 / API 25. Its protocol code supports older ADB protocol versions, but that does not prove compatibility with Fire OS 6. If the handshake fails, no fallback executable or shell runtime will be bundled.

No debloat, package, settings, launcher, Keeper, install or restore operation can be triggered from this GUI. The connection runs only fixed read-only commands: `getprop` and `pm list packages` inventory queries. It does not alter packages or settings beyond the optional ADB trust record that the owner explicitly approves.

The Rust migration foundations also include typed device compatibility gates, the existing Fire OS package-catalogue parser and an offline cleanup planner. The shell-to-Rust mapping and safety invariants are documented in `MIGRATION-PLAN.md`; ADB dependency decisions are documented in `ADB-RESEARCH.md`. The application does not bundle Google's platform-tools, Python, Bash, Git or any ADB executable.

## Build on Windows

Install the Rust stable toolchain once from [rustup.rs](https://rustup.rs/), then open PowerShell in this directory:

```powershell
cargo build --release
.\target\release\dumbtv-control-centre.exe
```

The resulting executable is self-contained with respect to the Rust runtime; Windows system libraries remain OS dependencies. The PR's Windows Actions workflow packages the EXE, this README and the third-party notices into a portable ZIP artifact. Extract it and run the EXE; no installer, admin rights or runtime installation are required. This test executable is not code-signed, so Windows may display its normal publisher warning. Only run a build downloaded from this repository's own Actions run.

## Dependencies and licensing

The application uses eframe/egui, reqwest, serde, dirs, tempfile, zip, libadb, Tokio, Windows CNG bindings, and RustCrypto digest/encoding crates. Their licences and the complete resolved dependency tree must be checked and recorded before a public release. This prototype does not redistribute Google's Android SDK Platform Tools or any third-party ADB executable.

The repository's MIT licence applies to this Control Centre source. Third-party dependencies remain under their own licences. This file is not legal advice or a substitute for the generated third-party notices required for distribution.

## Test checklist

1. Launch the executable on Windows 11 without installing Python, Bash, Git or ADB.
2. Check updates while online and while offline.
3. Download the latest source; confirm the commit SHA is shown.
4. Download the same commit again; confirm it does not overwrite the existing version.
5. Confirm an unrelated existing checkout and its local changes remain untouched.
6. Inspect the managed source folder and active manifest.
7. Prepare the ADB host key and record the MD5 fingerprint shown by the app.
8. On a physical AFTMM, connect to its local IP, compare the TV's RSA prompt fingerprint, and approve only if it matches.
9. Confirm the app reports the expected AFTMM / API 25 properties and package counts, and does not change packages or settings.
