# dumbtv — Fire TV Stick 4K (1st generation)

A focused, reversible Fire TV cleanup and replacement home-screen project, maintained as a fork of [the original DumbTV project](https://github.com/humblemuzzu/dumbtv).

**This fork's launcher is built specifically for one device family:**

- **Device:** Amazon Fire TV Stick 4K, 1st generation
- **Model identifier:** AFTMM
- **Fire OS:** 6.7.1.1
- **Android base:** Android 7.1.2
- **SDK/API:** 25

The launcher APK and its installer deliberately reject other Android SDK versions. This is not a general-purpose launcher build for Fire OS 7, 8 or newer Fire TV hardware.

The repository retains the wider DumbTV debloat scripts and device catalogue. Their support matrix is separate from the launcher target; do not assume that a device supported by the scripts can also run this fork's launcher APK.

---

## What this fork is for

The goal is a Fire TV Stick that starts reliably into a clean, fast home screen without Amazon's promotional home experience getting in the way.

This repository has two distinct parts:

1. **DumbTV home** — the custom launcher UI and Keeper recovery service. This is the part specifically adapted and restricted to the AFTMM / Fire OS 6 target.
2. **DumbTV cleanup scripts** — ADB-based configuration and reversible package-disabling tools inherited from the wider project. These retain their own device checks and compatibility information.

The launcher does not root the stick, replace Fire OS, modify the bootloader, bypass DRM or remove streaming applications.

## Changes made in this fork

The launcher has been adapted rather than treated as a generic Android TV application.

### Platform and build

- Fixed the launcher build to **minimum SDK 25 and target SDK 25**.
- Restricted the installer and release validation to **API 25**. An incompatible device is rejected instead of being offered an APK built for a different Fire OS generation.
- Kept the launcher in plain Java with Android framework views. The build uses JDK 17 and Android SDK platform 25/build-tools; it does not require Gradle, AndroidX, Kotlin, Compose or Play Services.
- Kept the APK free of the Android `INTERNET` permission. The launcher does not need network access, phone-home services, analytics or update checks.
- Added host-side tests and CI checks for launcher logic, manifest permissions, SDK levels, APK alignment and signing/verification.
- Added a validation APK artifact workflow and a separate signed release workflow. Release builds use signing credentials stored as GitHub Actions secrets; signing material is not committed to the repository.

### HOME takeover and Keeper

Amazon's launcher is a protected system HOME handler on this device. This fork does not attempt to replace Amazon's protected component or depend on winning the HOME intent-resolution priority.

Instead, **Keeper** detects when Amazon's Home activity becomes foregrounded and returns the existing DumbTV home task to the foreground.

The Fire OS 6 implementation has been hardened specifically for the AFTMM:

- Starts after boot and after the launcher package is updated.
- Runs as a foreground service and is exempted from Doze by the installer.
- Uses UsageStats foreground events as its primary detection path. The installer grants the required UsageStats AppOp.
- Uses ActivityManager logcat as an additional, more immediate signal when ADB grants `READ_LOGS`.
- Verifies the exact Amazon Home activity rather than reacting to every Amazon activity or every launch request.
- Keeps screen-on/off handling and UsageStats polling as independent recovery paths.
- Debounces duplicate foreground events, handles stale UsageStats event windows after deep standby, and restarts a failed logcat reader with backoff.
- Attempts task reordering first and verifies that DumbTV actually returned. A limited activity-launch fallback is used if task reordering does not recover the launcher.
- Pauses takeover while the user is navigating Amazon's own settings screens, then resumes when a third-party app is opened.
- Does not intentionally wake the display, reboot the stick or interfere while the display is off.

The revised standby-wake takeover has now passed an initial physical test on the target stick. Longer overnight-standby testing is still pending; it should not yet be described as universally proven across every standby duration or firmware state.

### Installer and release delivery

`launcher/install.sh` is designed around the AFTMM/API 25 deployment:

- Reads the launcher version from `launcher/version.env`.
- Downloads the matching APK from this repository's GitHub Release, not from an arbitrary build artifact.
- Downloads and verifies the matching SHA-256 sidecar before installation.
- Installs or updates the APK, applies the required Fire OS 6 AppOps/permissions, adds the Doze exemption and starts the launcher.
- Checks that Keeper is running and that DumbTV is in the foreground.
- Requests `READ_LOGS` on each install/update and verifies the resulting permission state using `dumpsys package`. Fire OS 6's `pm check-permission` output is not reliable on this device.
- Retains UsageStats recovery if `READ_LOGS` cannot be granted or verified.
- Provides an undo path to return HOME to Amazon's launcher.

The release workflow is tag-driven (`launcher-v*`). The validation workflow builds and tests the source; the signed release workflow publishes the APK and checksum sidecar. See [Building and releasing the launcher](docs/BUILDING-LAUNCHER.md).

### Launcher UI and usability

The inherited launcher UI has also been refined for remote control and the target screen:

- Rows and adaptive grid layouts, app ordering and pinned/all-app views.
- Remote-friendly options navigation and custom HSV colour selection.
- Custom backgrounds, wallpapers, gradients and adaptive background/tint modes.
- Configurable tile shape, spacing, borders, shadows, focus rings and focus animation.
- Original, Afterglow and Brand Focus app-tile styles.
- Icon-pack support and per-app icon selection.
- HDMI input tiles, child lock, memory display and optional library banner.
- Fire OS 6-specific settings navigation to avoid Amazon's partially off-screen settings layout.

See [launcher/README.md](launcher/README.md) for the full controls, settings and configuration reference.

---

## Removed or intentionally unsupported

These are deliberate scope decisions, not unfinished features to re-enable by default.

| Removed / excluded item | Reason |
|---|---|
| **Guard accessibility service** | Fire OS 6.7 on the target AFTMM does not provide a dependable path for the third-party accessibility-service takeover used by newer Fire OS variants. Keeper is the single recovery mechanism for this build. Removing Guard also removes its accessibility declaration, configuration/resources, installer setup and related retry logic. |
| **Fire OS 7/8 launcher compatibility paths** | They served different Android versions and used a different takeover model. Keeping them in an API 25-only build added branches and permissions that this device does not need and made the supported target less clear. |
| **Accessibility-based HOME interception and overlay/alarm takeover logic** | These belonged to the removed Guard path. The AFTMM build uses foreground detection and task recovery instead. |
| **Fire OS 8 media-folder compatibility handling** | The launcher is restricted to API 25, so the newer storage compatibility branch is unnecessary. |
| **Legacy Guard-only alarm timing/retry helpers** | They existed to support the removed accessibility takeover and are not part of Keeper's Fire OS 6 recovery path. |
| **On-device personalDNSfilter lifecycle integration in the launcher** | The launcher should not start, restart or supervise a separate VPN/DNS-filter app. This reduces coupling and avoids making launcher reliability depend on another app. The installer retains a one-time migration cleanup for legacy personalDNSfilter configuration; it does not install or run that filter. |
| **Generic multi-version launcher release target** | A single APK intended for multiple Fire OS generations obscures the actual permission and takeover requirements. This fork publishes an API 25 launcher for the AFTMM only. |

The wider repository's optional network filtering guidance is separate. DumbTV itself does not run a DNS filter or VPN on the stick. For network-wide filtering, use the separate [Pi-hole guide](docs/PI-HOLE.md).

## Install the launcher

On the Fire TV Stick, enable ADB debugging and note its IP address. On a computer with Android platform-tools installed:

```sh
adb connect <firestick-ip>:5555
./launcher/install.sh
```

Accept the ADB authorisation prompt on the television. The installer checks the device SDK and refuses to proceed if it is not API 25.

To undo the launcher takeover:

```sh
./launcher/install.sh --undo
```

This returns HOME handling to Amazon's launcher. It does not factory-reset the stick or uninstall the user's streaming apps.

For the full debloat workflow, start with [scripts/preflight.sh](scripts/preflight.sh), review the proposed changes, and use the scripts' confirmation prompts. The package-disabling workflow is separate from installing the launcher. Every package change made by the cleanup scripts is designed to be reversible with:

```sh
./scripts/restore.sh
```

Do not run the cleanup scripts blindly. Review the device-specific notes in [catalog/](catalog/README.md) and [docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md) first.

## Build and release

The launcher is built directly from the checked-in source:

- JDK 17
- Android SDK platform 25
- Android build-tools 34.0.0
- `launcher/build.sh` — local build
- `.github/workflows/validate.yml` — source tests and unsigned validation build
- `.github/workflows/release-launcher.yml` — signed APK release on `launcher-v*` tags

No prebuilt third-party launcher binary is pulled into the build. The release installer downloads the APK and checksum from this repository's matching release tag.

See [docs/BUILDING-LAUNCHER.md](docs/BUILDING-LAUNCHER.md) for SDK setup, signing secrets and release steps.

## Repository guide

| Path | Purpose |
|---|---|
| `launcher/` | Launcher source, Keeper, manifest, build and installer |
| `scripts/` | ADB cleanup, setup, verification and restore scripts |
| `docs/` | Technical explanations, troubleshooting and Pi-hole guidance |
| `catalog/` | Device identifiers, Fire OS versions and device-specific notes |
| `.github/workflows/` | Validation and signed launcher release workflows |
| `AGENTS.md` | Repository instructions for coding agents |

## Relationship to the original project

This repository is a fork of [humblemuzzu/dumbtv](https://github.com/humblemuzzu/dumbtv). The original project's debloat approach, documentation structure, device catalogue and much of the launcher foundation remain upstream-derived.

The AFTMM-specific launcher changes documented above are maintained here for a single known Fire TV Stick and Fire OS version. They should not be assumed to apply to the upstream project or to other Fire TV devices.

When upstream changes are adopted, they must be checked against the API 25 manifest, Keeper lifecycle, installer, release workflow and physical AFTMM behaviour before being merged.

## Licence and disclaimer

Scripts, documentation and launcher source are licensed under the repository's [MIT licence](LICENSE), except where a subdirectory states otherwise. The device catalogue has its own [licence](catalog/LICENSE).

Fire TV and Fire OS are trademarks of Amazon. This project is independent and is not affiliated with or endorsed by Amazon. Use ADB changes at your own risk and keep a working restore path.
