# Fork architecture and operating guide

This document describes the behaviour of this fork as it exists in the repository. It is the reference for the relationship between the inherited cleanup tools and the AFTMM-specific launcher. It should be read with [the project README](../README.md), [How it works](HOW-IT-WORKS.md), and the [launcher reference](../launcher/README.md).

## 1. Scope and support boundaries

This fork has two related but separately supported products:

| Component | Supported scope |
|---|---|
| ADB cleanup and restore scripts | Fire OS 6, 7 and 8, subject to each device's package lists and preflight checks |
| DumbTV home APK and HOME recovery | Amazon Fire TV Stick 4K (1st generation), model AFTMM, Fire OS 6.7.1.1, Android 7.1.2 / API 25 |
| Device catalogue | Reports and reference data for additional Fire TV and other television models; a catalogue entry does not make the launcher or scripts universally compatible |
| Pi-hole exports | Optional external DNS policy for a separately managed Pi-hole installation; not an on-device launcher feature |

The launcher build has both minimum and target SDK fixed at 25. Its install/build path rejects a device reporting another SDK. The `--undo` path is intentionally not API-gated so an existing installation can hand HOME back to Amazon on another Fire OS generation.

Fire OS 5 and older are not supported by the cleanup scripts. Android TV/Google TV entries in the catalogue are reference material for manual work; the Fire OS scripts refuse those platforms. This fork does not provide a general-purpose Android TV launcher.

## 2. What is in the repository

- `scripts/`: ADB preflight, configuration, package cleanup, privacy settings, app-ops/network policy, launcher deployment, optional YouTube replacement, verification and restore.
- `launcher/`: Plain-Java Android launcher, Keeper recovery service, build/install/undo scripts, version manifest and UI documentation.
- `data/fireos/`, `data/fireos6/`: Fire OS package, setting and policy lists. Fire OS 6 selects its own list set; Fire OS 7/8 use the shared Fire OS lists.
- `data/androidtv/`: reference lists only; the cleanup scripts do not apply them.
- `data/blocklists/`: source domains, evidence/metadata and generated Pi-hole exports for different Pi-hole/FTL generations.
- `catalog/`: device identifiers, observed setup paths, quirks and validation schema.
- `docs/`: operational, recovery, DNS, device and platform documentation.
- `.github/workflows/`: source validation and tag-driven signed launcher release.

## 3. First-time setup

### Requirements

- A computer with ADB platform-tools and Bash.
- A Fire TV with ADB debugging enabled and an authorised ADB connection.
- One target TV connected at a time. If more than one device is listed by `adb devices`, select the intended one with `ADB_SERIAL`.
- For building the launcher locally: JDK 17, Android SDK platform 25 and Android build-tools 34.0.0.

Enable Developer Options on the Fire TV, enable ADB debugging, connect using the IP shown by the TV, then check the device identity:

```sh
adb connect <firestick-ip>:5555
adb devices
adb shell getprop ro.product.manufacturer
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.device
```

The target launcher reports manufacturer Amazon, model/device AFTMM and SDK 25. Do not infer launcher compatibility from the fact that the cleanup scripts recognise a different Fire TV.

### Configure and run the cleanup

The normal guided path is:

```sh
./scripts/preflight.sh
./scripts/configure.sh
./scripts/all.sh
./scripts/verify.sh
```

- `preflight.sh` identifies the device, checks support and records the settings needed for restoration. It is a read-only inspection apart from its local report/snapshot files.
- `configure.sh` records household choices in the git-ignored `dumbtv.conf`. With no flags it asks about Alexa, replacing the home screen, apps to remove and YouTube replacement. Defaults keep Alexa, enable the launcher, remove no user apps and leave YouTube alone.
- `all.sh` runs the workflow in order: preflight, configuration if no config exists, legacy personalDNSfilter cleanup, debloat, lockdown, launcher installation if selected, optional YouTube replacement, then verification. It reuses saved choices; it does not ask the questions again when `dumbtv.conf` already exists.
- Every modifying script supports `--dry-run`. Review its output before approving changes, especially when changing a device or package list.

For a staged/manual run, follow the sequence in [AGENTS.md](../AGENTS.md). Do not skip preflight or assume that a package list for another model is safe.

### What the configuration means

`dumbtv.conf` contains only the choices made for the household: packages to keep, packages explicitly named for removal, Alexa preference, launcher preference and YouTube preference. It is local state and must not be committed. It is not the launcher's own settings file.

The launcher has a separate `home.conf` on the TV. It stores home-screen appearance, layout, app ordering, favourites/pins, widget visibility and placement, wallpaper rotation, and other launcher preferences. Export it from Options or pull it from the app's external-files directory before uninstalling or changing signing keys.

## 4. How the cleanup works

The cleanup is reversible ADB administration. It does not root the TV, remount or write to `/system`, modify the bootloader, or replace Fire OS.

1. **Package state:** Amazon advertising, sponsored-content, telemetry, experiment and related packages are disabled for user 0 with `pm disable-user`; their APKs remain on the system partition. The selected `never-disable.txt`, `keep` and user-app lists protect essential services and apps.
2. **Explicit app removals:** an app is only removed if the owner names it. A system app is disabled; a user-installed/store app is uninstalled for user 0. The latter must be reinstalled from the Appstore when restoring.
3. **Privacy settings:** the scripts apply the Fire OS settings keys in the matching data files. `preflight.sh` captures original values where available so restore can put them back.
4. **Protected packages:** when Fire OS refuses to disable a package, `lockdown.sh` applies background AppOps and Data Saver/network-policy restrictions to the relevant protected packages. This is not equivalent to stopping a process or blocking all traffic: Data Saver restrictions apply to metered networks.
5. **Verification:** `verify.sh` checks package state, selected privacy settings, policy and launcher state. A warning or failure needs investigation; do not add more packages to the disable list as a first response.

The package lists are model/OS-specific. `never-disable.txt` is a hard safety boundary, not a suggestion. `protected.txt` contains packages that Fire OS will not disable and need the separate restrictions.

### Restore and what restore cannot do

Run:

```sh
./scripts/restore.sh
```

Restore re-enables packages covered by the current and legacy lists, removes the app-ops/network restrictions applied by the scripts, restores saved settings (or documented defaults where no snapshot exists), restores a screensaver changed by the cleanup, clears the legacy personalDNSfilter VPN selection only when it points to that app, and hands HOME back to Amazon.

Restore does **not** factory-reset the TV. It does not uninstall DumbTV home or SmartTube; both remain installed. It cannot recreate a store app that was uninstalled for the owner: reinstall that app from the Appstore. The restore report explains the remaining cleanup and uninstall steps.

## 5. DNS filtering is separate

This fork does not install, run or supervise personalDNSfilter or another local VPN/DNS-filter app. The launcher has no `INTERNET` permission. It requests `ACCESS_NETWORK_STATE` only for the local network-status widget; that permission does not grant outbound network access.

The legacy cleanup step exists only to migrate an older installation: it disables the old personalDNSfilter package and clears its always-on VPN selection if that selection names personalDNSfilter. It does not configure Pi-hole or change the router's DNS settings.

Pi-hole is an optional, independent network service. Its lists live under `data/blocklists/`; the generated exports distinguish Pi-hole v6, v5 with FTL 5.22+, and older v5 formats. Alexa and Prime Video blocking are opt-in. The default policy is intended to preserve those services. Follow [the Pi-hole guide](PI-HOLE.md), use a dedicated client group, and confirm effects in Pi-hole's Query Log. DNS blocking can break shared Amazon services and is not a reliable way to remove in-stream YouTube ads.

## 6. How the launcher and Keeper work

### Build and deployment

The launcher is plain Java using Android framework views. It is built directly with `aapt2`, `javac`, `d8`, `zipalign` and `apksigner`; there is no Gradle, Kotlin, AndroidX, Compose, Play Services or bundled launcher framework.

- Package: `tv.dumbtv.home`
- Minimum SDK: 25
- Target SDK: 25
- Build toolchain: JDK 17, Android platform 25, build-tools 34.0.0
- Manifest permissions are deliberately limited; in particular, there is no `INTERNET`, accessibility service, overlay permission or alarm-based takeover permission.

The normal installer downloads the matching APK from this fork's GitHub Release and verifies its SHA-256 sidecar before installing. `--build` builds from the checked-out source. The release APK must be signed with the same persistent certificate for every update. A differently signed APK cannot update an existing installation; the installer stops rather than silently uninstalling it.

### HOME recovery

Amazon's HOME activity remains the protected system handler. DumbTV does not replace or disable it. Keeper restores the existing DumbTV home task after it positively detects Amazon Home in the foreground.

- The boot receiver handles locked-boot, normal boot and package-replaced events, then waits for the user to unlock before starting Keeper.
- Keeper runs as a foreground service.
- The installer grants and verifies UsageStats access, Fire OS `AUTO_START`, and `android.permission.READ_LOGS`; it also adds the Doze exemption and starts the service.
- UsageStats foreground events are polled. ActivityManager logcat provides a second, more immediate signal for the exact Amazon Home activity. Screen on/off broadcasts provide additional recovery context.
- The event cursor avoids relying on a fixed short look-back after deep sleep. Recovery attempts are debounced and verified; task reordering is attempted first, with a bounded activity-launch fallback.
- Amazon settings navigation pauses takeover so Keeper does not immediately reclaim the screen. The pause is cleared when a third-party app becomes foregrounded.

`READ_LOGS` is required by this fork's installer because the ActivityManager signal is part of the AFTMM recovery design. It is not documented as optional. UsageStats and screen broadcasts remain separate signals, but a successful install must verify all required setup steps.

Keeper does not intentionally wake the display, reboot the TV or interfere while the display is off. See [Keeper standby and HOME recovery](KEEPER-RECOVERY.md) for the original failure, the fix history and remaining physical-testing limits.

## 7. Launcher UI and settings model

DumbTV home is a remote-first launcher built from native Android views. Its main areas include the top bar, favourites/pinned apps, app and input sections, optional All Apps, optional Library banner, wallpaper/background treatment and configurable widgets.

- **Options** is DumbTV's own settings screen. Rows show their current values; OK opens a choice or picker, and supported values can be cycled with Left/Right. Changes are saved to `home.conf`.
- **Fire OS Settings** is a separate destination. The Settings shortcut opens Amazon's settings interface through a Fire OS 6-specific section list where available; it is not another DumbTV options page.
- **Widgets** have separate visibility and placement. Turning a widget off preserves its saved position. The editor stages placement changes until Save; Cancel discards those edits.
- **Favourites/pins** are launcher navigation state, not Android package favourites. Hiding a section does not uninstall its apps.
- **Wallpaper and Library assets** are files stored beside the launcher configuration. Export settings does not bundle those image files; back them up separately.

The launcher reference documents the controls and the current glass theme/widget layout. Do not infer that a UI feature changes Keeper or the debloat scripts: these are separate layers.

## 8. Widgets

The home screen uses a fixed 6-column by 4-row placement grid. Widget placements are stored in `home.conf`; the layout model rejects placements outside the grid, overlaps and sizes below 1 × 2 cells.

The available widget types are:

- Clock
- Memory
- Network connection state
- Storage
- Custom text
- DumbTV diagnostics
- Device information

The clock is the initial placement on a fresh widget layout. Visibility settings are independent of placement: a hidden widget keeps its position, and an enabled widget without a placement is shown as not added in Widget Settings. The editor supports adding, moving, resizing and removing widgets; changes are staged until Save. The network widget reads Android's active network state and does not make network requests.

## 9. Validation and releases

The GitHub Actions validation workflow checks three areas:

- **Launcher:** API 25 build, host-side Java tests, APK package/version/SDK identity, alignment, signature/checksum and exact manifest permission set.
- **Catalogue:** YAML/schema and device catalogue validation.
- **Scripts:** Bash syntax, offline fake-device tests, Pi-hole export consistency, launcher version manifest and checks for private addresses/hardware identifiers in committed files.

These checks do not replace a physical AFTMM test. CI cannot prove remote-control navigation, actual Fire OS AppOps grants, HOME takeover, HDMI passthrough, or long standby/wake behaviour.

A signed release is created by pushing a tag matching `launcher/version.env`, such as `launcher-v1.11`. The release workflow uses the persistent Actions secrets `DUMBTV_KEYSTORE_BASE64` and `DUMBTV_KEYPASS`, builds and verifies the signed APK, then publishes the APK and SHA-256 sidecar. See [Building and releasing the launcher](BUILDING-LAUNCHER.md).

## 10. Changes from upstream

This is an independently maintained fork of [humblemuzzu/dumbtv](https://github.com/humblemuzzu/dumbtv), not a promise to track upstream launcher changes.

The fork retains the upstream project's broad ADB cleanup approach, much of its device catalogue and documentation, and the native Java launcher foundation. Its independent work includes:

- An AFTMM-only API 25 launcher build/install/release path.
- A Fire OS 6 Keeper implementation based on UsageStats, ActivityManager logcat and verified task recovery rather than the former cross-version accessibility/overlay/alarm takeover paths.
- The deep-sleep event-cursor and wake-recovery fixes documented in [Keeper standby and HOME recovery](KEEPER-RECOVERY.md).
- Removal of the on-device personalDNSfilter/VPN dependency, with optional external Pi-hole exports and migration cleanup for existing installations.
- Fork-specific validation, host tests, installer permission checks and signed release delivery.
- Launcher UI work for the glass theme, home layout, favourites, widget placement and visibility, and reorganised Options navigation.

Upstream changes must be reviewed against the AFTMM manifest, Keeper lifecycle, installer, restore path, build/release process and physical device behaviour before adoption.
