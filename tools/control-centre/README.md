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
- The installer grants and verifies the device-idle whitelist, UsageStats AppOp, optional vendor AUTO_START AppOp, and Keeper READ_LOGS permission.
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

## Verified APK assets

The GUI can download and SHA-256 verify the pinned API 25 DumbTV HOME release (version metadata from the active source) and the optional SmartTube 32.56s release (pinned versionCode and checksum from the maintained YouTube script). Assets are stored under `%LOCALAPPDATA%\\DumbTV-ControlCentre\\verified-apks` without overwriting an existing file. The SmartTube download is an explicit owner action.

This is currently an asset-preparation milestone only. It does not install either APK, modify Keeper or permissions, disable stock YouTube, or edit DumbTV HOME configuration. Those operations remain gated until their device-state snapshot, rollback and verification paths are complete. A pure, offline-tested home.conf transformer is also in place: it inserts SmartTube at the front of apps.order, removes stock YouTube and preserves unrelated settings. It is not connected to the device until exact-byte backup and restore are implemented. The Rust ADB sync staging primitive is implemented with a fixed `/data/local/tmp/dumbtv-*.apk` path allowlist, transfer bounds and full SHA-256 read-back verification, but the launcher/YouTube installation workflow is not yet wired to it.

## Snapshot, cleanup and restore

The GUI now captures a complete pre-change baseline and refuses to overwrite it. Cleanup and restore each write a durable operation journal under `%LOCALAPPDATA%\\DumbTV-ControlCentre\\journals`; each operation is marked running before the ADB write and verified only after read-back succeeds. A separate read-only verification button can recheck all verified cleanup mutations later, including after reboot. Cleanup itself performs a second full read-back pass after the sequence; an optional reboot must show an ADB disconnect, reconnect within 120 seconds, pass same-device identity checks and repeat the verification. Journals omit device IPs, ADB serials, host keys and raw command output. An interrupted operation is not automatically replayed: reconnect and inspect the live device before choosing restore. Interrupted and failed journals are listed when the frontend starts, newest first, with verified, failed, running-at-last-save and pending counts plus the affected step summary. A controlled failure closes its journal and records later steps as not attempted; a crash or power loss leaves the in-flight journal visibly unfinished. Before cleanup begins, it reconnects and compares the live package states, captured settings, AppOps and network-policy values against that baseline. If anything changed after capture, it sends no cleanup commands.

Cleanup is reviewed before execution and requires a separate final confirmation. Each operation is a typed command with validated arguments and a read-back check. A failure stops the sequence; completed operations are reported and the original snapshot is retained. The restore planner first calculates differences from the saved baseline, then scopes automatic restore to mutations recorded as verified in the matching device/source cleanup journal. It also checks that each live field still equals the value applied by that cleanup command; later edits to a different value are left untouched and listed for manual review. The journal stores typed command intent, never raw shell text. Restore still requires review and confirmation, and actions are verified individually. It does not recreate uninstalled APKs or assume defaults for unknown state.

The remaining work is guided reconciliation for unverified/interrupted steps, launcher/Keeper installation and undo, optional YouTube installation and home.conf backup/restore, and review of the generated third-party licence report and committed Cargo.lock. No physical AFTMM test has been performed. Do not treat this draft PR as a signed public release.
