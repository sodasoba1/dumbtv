# DumbTV Windows Installer — test build

This is the portable Windows GUI installer and maintenance frontend for the AFTMM DumbTV fork. It is distributed as an extract-and-run ZIP rather than an MSI. It installs the existing published Android launcher APK; it does not replace or rebuild the Android launcher source, modify Keeper, or execute the existing scripts.

## What this test build does

- Checks the official `sodasoba1/dumbtv` `main` branch through the GitHub HTTPS API.
- Downloads the official source archive for the exact returned 40-character commit, then retains only the maintained `data/` catalogues and `launcher/version.env` needed by the Windows controller.
- Does not extract Android source, Gradle projects, APK build inputs or shell scripts into the managed cache. The existing scripts remain behavioural references and are never executed.
- Rejects unsafe paths and symbolic links, and enforces archive/file-count/extracted-size limits.
- Validates all supported package catalogues, the protected `com.amazon.vizzini` invariant, privacy settings and launcher version metadata before writing the active-source manifest.
- Never overwrites a pre-existing repository folder and never executes downloaded scripts.

Managed files are stored under:

`%LOCALAPPDATA%\DumbTV-ControlCentre\source-versions\<commit-sha>\`

The active source manifest is stored separately in `active-source.json`. Existing checkouts and user configuration are not touched.

## Available in this test build

- Prepare a dedicated 2048-bit ADB host key in the current Windows user's CNG key store. The private key is not exported.
- Display the Android 7-era MD5 fingerprint for comparison with the Fire TV authorisation prompt and a separate SHA-256 fingerprint.
- Connect to one owner-entered private/local IP address on TCP 5555 using Rust libadb and the Windows CNG authenticator.
- Read device properties, package inventories and installed protected-package UIDs with bounded response buffers. The connection fails closed outside the supported Amazon Fire OS SDK 25–30 cleanup range.
- Capture original package states and user/system classification, relevant secure/global settings (including originally absent keys), protected-package AppOps, Data Saver and the complete metered-data UID blacklist. The snapshot is pinned to the exact source commit whose catalogues were used and cannot be overwritten.
- Build a visible cleanup plan from the maintained catalogues. Alexa is kept by default; system packages are disabled rather than uninstalled; user apps are only uninstalled when individually named; never-disable entries and com.amazon.vizzini are protected.
- Apply cleanup only after a separate final confirmation. Operations are sent as typed Rust-generated ADB commands, verified by reading the resulting state back, and stop at the first failed command or verification.
- Review and execute a delta-only restore plan. Settings originally absent are deleted again; AppOps and network policy are restored to captured values. Missing user apps are listed for manual reinstall from their original source (Amazon Appstore or sideloaded APK); missing system packages are reported for manual recovery.
- Restrict the connection target to private, loopback or link-local addresses.

On a first connection, the Fire TV may ask to authorise this PC's ADB public key. Compare the MD5 fingerprint shown in the app with the TV prompt before allowing it. The TV adds the key to its trusted ADB host list only if you approve. Selecting "Always allow" on the TV saves that authorisation for later connections.

**ADB compatibility note:** libadb's published support range lists Android 9+, while the target AFTMM runs Android 7.1.2 / API 25. Its protocol implementation includes legacy ADB negotiation and shell v1, and the Windows build/tests pass. I am treating the transport as working as requested; there is no observed protocol failure, but a physical AFTMM connection remains the final compatibility check.

### DumbTV HOME and Keeper

- Install/update the existing published launcher APK for Amazon API 25 only. The installer reads `launcher/version.env`, downloads the release asset and SHA-256 sidecar, and re-verifies the bytes immediately before staging.
- A signature mismatch fails without uninstalling the existing package or deleting its settings.
- The installer grants and verifies the device-idle whitelist, UsageStats AppOp, and Keeper READ_LOGS permission.
- It starts `tv.dumbtv.home/.Home`, verifies `tv.dumbtv.home/.Keeper` is foreground, and checks that DumbTV HOME is the resumed activity. A parental-control PIN prompt is reported as a possible reason the foreground check cannot complete.
- Before changing runtime grants it stores the prior AppOps, READ_LOGS and idle-whitelist state locally. Undo hands HOME back to Amazon and restores those captured values. The launcher APK remains installed.

### Optional SmartTube replacement

- SmartTube is disabled by default in the UI and requires an explicit opt-in before installation.
- Downloads the pinned SmartTube 32.56s release and verifies its SHA-256. After installation it reconnects to ADB and checks the installed versionCode (2446 or newer), retrying once if the package is not present at the required version.
- Stock YouTube is disabled reversibly only if it was installed and enabled. It is never uninstalled.
- If DumbTV HOME and a non-empty `home.conf` exist, the installer backs up the exact original bytes, changes only `apps.order`, places SmartTube first and removes stock YouTube from that order. It checks the live file has not changed since backup and verifies the bytes written.
- Undo restores the original YouTube enabled/disabled state and exact `home.conf` bytes. If the user has edited the configuration since installation, undo refuses to overwrite those later changes. SmartTube itself remains installed to preserve its user data.
- One-time SmartTube UI choices still require the remote: enable “Hide shorts everywhere” and select the 1080p 60fps VP9 preset (not AV1).

The installer does not modify or rebuild Android source or the published launcher APK. Existing shell scripts remain behavioural references and are never executed. It does not install/configure DNS or VPN filtering. Cleanup performs a second complete read-back pass after all operations. An optional, separately selected reboot waits for an observed ADB disconnect, reconnects within a strict 120-second deadline, rechecks device identity and repeats the cleanup verification. For cleanup packages that remain running after disable, the app applies the matching background AppOps restrictions, adds an app-specific UID to the metered-data blacklist where safe, then force-stops and verifies the process has exited. Protected packages receive their separate lockdown policy. Process detection uses the API 25 fallback from the existing scripts. Never-disable packages are excluded. The app does not bundle Google's platform-tools, Python, Bash, Git or any ADB executable.

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

## End-to-end setup workflow

The current test build wires the reviewed cleanup, DumbTV HOME installation and optional SmartTube installation into one guided sequence:

1. Connect to the owner-selected Fire TV IP and complete the read-only device/package inspection.
2. Download and validate the maintained source catalogues, then capture the original package/settings/AppOps/network-policy state. The snapshot is immutable and tied to the device fingerprint and exact source commit.
3. Review the generated cleanup plan, including skipped/protected packages and any individually selected user-app removals.
4. Confirm the operation in a separate confirmation dialog. The installer rechecks that the device still matches the captured baseline before sending any cleanup command.
5. Apply typed operations one at a time, journal each step before the write, and verify each result by reading the state back. A failed operation stops the sequence.
6. If selected and supported, install the published DumbTV HOME API 25 APK after cleanup and any selected reboot/reconnect verification.
7. If SmartTube was explicitly selected, install it after DumbTV HOME, reversibly disable stock YouTube, and update `home.conf` only after saving its exact original bytes.
8. Review and apply the selective restore plan if rollback is required.

The installer does not blindly replay operations after a crash or connection loss. An interrupted or failed journal is displayed on startup. Reconnect to the same device, inspect its live state, then use the normal read-only verification and restore-review controls. Only mutations recorded as verified, whose live values still match the values applied by that cleanup journal, are eligible for automatic restoration. Uncertain or subsequently changed state is left untouched and reported for manual review.

The current recovery surface is deliberately conservative but not yet a complete novice-guided reconciliation wizard. It provides journal summaries and safe restore planning; it does not automatically resume or retry interrupted steps.

## Build and test

The pull request's Windows Actions workflow builds and packages the portable test ZIP. The artifact contains:

- `dumbtv-control-centre.exe`
- This README
- The repository MIT licence
- Third-party notices and the complete generated third-party licence texts
- The exact resolved `Cargo.lock`

The ZIP is unsigned. Windows may show its normal unknown-publisher warning. Only use the artifact downloaded from the linked run in this repository.

No separate runtime installation is needed. The executable uses Windows system libraries and does not bundle or invoke Python, Bash, Git, PowerShell or an external ADB executable.

## Test checklist

**Windows application**

1. Extract the ZIP and launch the EXE on Windows 10/11 without installing Python, Bash, Git or ADB.
2. Check source updates online and offline; download a source revision and confirm its SHA is displayed.
3. Download the same revision again and confirm the existing immutable source directory is not overwritten.
4. Confirm unrelated repository checkouts and user configuration remain untouched.
5. Inspect the managed source cache and active-source manifest.

**Read-only Fire TV connection**

6. Prepare the ADB host key and compare the displayed MD5 fingerprint with the Fire TV authorisation prompt. Do not approve a mismatched key.
7. Connect to an AFTMM on the local network and confirm the model, build, SDK and package inventory are correct.
8. Capture the original state and confirm that this action changes no device packages or settings.

**Device-changing tests — use a test device**

9. Review the cleanup plan and verify protected packages and never-disable entries are excluded.
10. Confirm no user app is uninstalled unless individually selected.
11. Apply cleanup and confirm every operation is journalled and read-back verified.
12. Test cleanup verification after reconnect/reboot.
13. Test selective restore and confirm changes made outside the installer are not overwritten.
14. Test DumbTV HOME installation from the published API 25 release, then test launcher undo.
15. Test SmartTube only after explicit opt-in; verify stock YouTube is reversibly disabled and `home.conf` is backed up exactly.
16. Edit `home.conf` after installation and confirm SmartTube undo refuses to overwrite that later edit.
17. Inspect interrupted/failed journal summaries and confirm no operation is automatically replayed.

Physical AFTMM testing is still required. The successful Windows CI build and offline tests do not prove ADB compatibility or device-side behaviour. Do not use a primary device for the first cleanup/restore tests.

## Compatibility and safety boundaries

- Cleanup supports Fire OS SDK 25–30. DumbTV HOME/Keeper installation is restricted to API 25. Unsupported SDKs fail closed.
- No root, remount, `/system` writes, bootloader operations or factory reset.
- Never disable never-disable entries or `com.amazon.vizzini`.
- No user app is removed unless individually selected and confirmed. Alexa remains an explicit choice.
- SmartTube is optional; stock YouTube is not removed.
- No DNS-filter or VPN installation/configuration. Legacy DNS-filter cleanup is only eligible when detected and explicitly approved.
- Keeper remains part of the existing DumbTV APK and is not split out, modified or rebuilt.
- The installer downloads and verifies the already-published launcher APK; it does not build or alter Android source or APK assets.

## Licensing

The Control Centre source is covered by the repository MIT licence. Dependencies retain their own licences. The Windows workflow audits the resolved dependency graph and generates complete third-party licence texts from the locked dependency set, including the accepted Ubuntu Font Licence expression. The generated licence inventory is included in the test ZIP. This documentation is not legal advice.
