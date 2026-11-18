# Shell-to-Rust migration plan

This document is the migration checklist for the Windows Control Centre. The
existing shell scripts remain the reference implementation and are not run by
the Control Centre. No existing script, launcher code or Keeper code is changed
by this migration.

## Completed Rust foundations

- `device_profile.rs`: parses Android device properties and applies the same
  compatibility boundary as the scripts: Amazon Fire OS SDK 25–30 for cleanup,
  API 25 only for DumbTV HOME/Keeper installation. Unknown newer SDK levels
  fail closed.
- `catalogue.rs`: parses the existing package lists without sourcing shell
  files. It preserves list order, strips comments, rejects malformed package
  identifiers, and mirrors the Fire OS 6 list fallback used by `fos()`.
- `package_inventory.rs`: parses read-only package-manager output into
  installed, system, user and disabled sets; accepts the special `android`
  framework package and rejects inconsistent inventories.
- `package_plan.rs`: builds an offline, read-only cleanup plan from the
  catalogue, installed/system package inventories and explicit owner choices and original enabled/disabled state.
  Defaults keep Alexa; already-disabled packages are skipped; named system apps
  are disabled; named user apps are uninstalled; never-disable entries are
  rejected; contradictory keep/remove choices fail closed.
- Unit tests cover API 25/28/30, unsupported manufacturers, older/newer SDKs,
  getprop parsing, catalogue parsing, actual repository catalogues, duplicate
  entries, generation fallback and cleanup-plan safety rules.

## Script migration matrix

| Existing script or helper | Rust destination | Behaviour that must be preserved |
|---|---|---|
| `scripts/lib.sh` | `adb/`, `device_profile.rs`, `catalogue.rs`, `snapshot.rs`, `operations/` | Single-device selection, lost-connection distinction, compatibility checks, package-list fallback, never-disable policy, dry-run planning, reconnect and bounded timeouts. |
| `scripts/preflight.sh` | `preflight.rs` | Read-only identity and current-state inventory; show proposed actions; save original settings only once before the first modification. |
| `scripts/configure.sh` | `configuration.rs` | Alexa kept by default; launcher choice; YouTube off by default; no app removed unless individually named; validate package IDs and keep-list entries. |
| `scripts/cleanup-legacy-dnsfilter.sh` | `operations/legacy_cleanup.rs` (separately gated) | Only retire the legacy personalDNSfilter integration when present and only clear its always-on setting when it names that app. Do not install or configure any DNS filter or VPN. |
| `scripts/debloat.sh` | `operations/packages.rs`, `operations/settings.rs` | Disable only present catalogue packages; never touch never-disable entries; honour keep choices; named system apps are disabled, named user apps are uninstalled; preserve original package states; handle protected/system-disabled/suspect outcomes; apply generation-specific privacy settings and screensaver fallback. |
| `scripts/lockdown.sh` | `operations/background_policy.rs` | Data Saver, protected-package background AppOps and per-UID metered-data blacklist; skip absent packages, shared system UIDs and keep-list entries; verify results. |
| `scripts/launcher.sh` + `launcher/install.sh` | `operations/launcher.rs` (later milestone) | API 25 gate; pinned release checksum; install; exact UsageStats, optional AUTO_START and READ_LOGS grants; verify Keeper and HOME; undo path. Keeper and launcher source remain untouched until parity is independently validated. |
| `scripts/youtube.sh` | `operations/youtube.rs` (opt-in milestone) | YouTube remains untouched by default; pinned SmartTube URL, SHA-256 and version; install verification; owner-selected disable versus uninstall; preserve unrelated DumbTV home configuration. |
| `scripts/verify.sh` | `verification.rs` | Read-only verification of HOME/Keeper, package state, privacy settings, Data Saver and launcher version; report warnings separately from hard failures. |
| `scripts/restore.sh` | `restore.rs` | Restore only packages enabled in the original snapshot; restore settings from original snapshots or documented defaults; clear only the project-owned network-policy changes; hand HOME back; accurately report that store apps uninstalled for user 0 require store reinstall. |
| `scripts/dns-preflight.sh` | Optional desktop diagnostic | Read-only DNS diagnostics only. It is not part of device cleanup and must not mutate Pi-hole, Unbound, VPN or device DNS settings. |

## Implementation order

1. **ADB transport and authentication:** select a Rust protocol library and a
   maintained signing implementation; validate legacy TCP ADB and RSA trust on
   the AFTMM. Until then, the GUI exposes no connection or device-changing
   controls.
2. **Read-only inventory:** connect to one explicitly selected device, collect
   the minimum required properties, package states, settings and capabilities,
   and build a report. No device writes.
3. **Configuration and plan:** load the source catalogue, validate owner
   choices, calculate the exact proposed operations, and display them without
   executing them.
4. **Snapshots:** save package enabled/disabled state and relevant settings
   atomically to local app data before the first device write. Never replace an
   existing original snapshot with a later, already-modified state.
5. **Reversible cleanup:** implement package disable, privacy settings,
   background policy, screensaver fallback and post-change verification.
6. **Restore:** implement and test restore before exposing cleanup as a normal
   user workflow.
7. **Launcher:** only after separate AFTMM validation of APK signing, grants,
   Keeper lifecycle, reboot/reconnect and undo.
8. **Optional YouTube replacement:** last, with explicit opt-in and pinned
   checksum/version verification.

## Safety invariants

- No generic command runner or arbitrary shell text in the UI. Rust code builds
  each reviewed device operation from validated typed values. The ADB protocol's
  remote shell service is used only where Android exposes no equivalent API.
- Exactly one connected/selected device per operation. A serial change or
  multiple-device result aborts the workflow.
- No writes to `/system`, root, remount, bootloader or factory reset.
- `never-disable.txt` is an absolute deny-list; `com.amazon.vizzini` is
  protected regardless of catalogue drift.
- No named user app is removed unless the owner explicitly selected it.
- YouTube replacement is always opt-in; Alexa is kept by default.
- The launcher remains API 25 only. Cleanup compatibility does not imply
  launcher compatibility.
- A lost connection is never interpreted as an absent package or a successful
  operation. Every write has a timeout and a subsequent read-back verification.
- Original snapshots are never silently overwritten. Restore limitations remain
  visible in the UI and report.
- Existing shell scripts, launcher code and Keeper implementation remain
  unchanged until Rust parity is demonstrated and the user approves that
  separate change.
