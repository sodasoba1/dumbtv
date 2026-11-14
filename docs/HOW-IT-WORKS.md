# How it works

Everything runs over ADB as the `shell` user (uid 2000), the same access Fire OS
gives you when you switch on ADB debugging. Nothing needs root, and nothing is
written to `/system`.

There are five layers. Each one exists because the layer before it cannot reach
everything.

## 1. Packages: disabled, never uninstalled

`debloat.sh` runs `pm disable-user --user 0 <package>` for every entry in
`data/fireos/packages.txt` (`data/fireos6/` on Fire OS 6). The code stays in
`/system`, the package stops running, and `pm enable` or the TV's own
Settings › Applications brings it back.

The list holds only Amazon's ads, viewing trackers (ACR), telemetry, experiments,
update nags and empty stubs. Three other lists decide what is left alone:

| List | What is in it | What happens to it |
|---|---|---|
| `never-disable.txt` | home screen, settings, inputs, tuner, remote pairing, Prime Video, Alexa's runtime | never touched, even if named |
| `alexa.txt` | Alexa and the app that owns the microphone | kept; disabled only with `ALEXA=0` |
| `user-apps.txt` | streaming apps, Amazon Music, Photos, Silk, the screensaver, printing | kept; removed only when named |

An app the owner names is disabled if it shipped with the TV, and uninstalled if
it came from a store.

When Amazon refuses (`Cannot disable a protected package`), the package moves to
layer 3. Which packages are protected differs between builds.

## 2. Settings: `settings put`

Ad tracking, interest-based ads, usage reporting, promo autoplay and animation
speed are `Settings.Secure` and `Settings.Global` keys, the same ones the
on-screen toggles write. They are in `data/fireos/settings.txt`. The wake word and
voice settings are in `settings.alexa.txt` and only apply with `ALEXA=0`. Fire OS
rewrites some keys at boot, which is one reason `debloat.sh` is safe to run again.

## 3. App-ops and network policy, for the packages Amazon protects

The shell cannot disable them, but it can take away what they need:

```sh
appops set <package> RUN_IN_BACKGROUND ignore
appops set <package> RUN_ANY_IN_BACKGROUND ignore    # Android 9 and later
cmd netpolicy set restrict-background true           # Data Saver
cmd netpolicy add restrict-background-blacklist <uid>
```

Their background services are held in standby. Data Saver refuses their background
data only on metered networks: a TV on Wi-Fi or Ethernet is not metered (`dumpsys
netpolicy` lists only the active network), so Pi-hole can optionally block their
DNS requests when configured on the network. A process that is already running keeps running,
and `am force-stop` is refused for them, so they are starved rather than stopped.
`lockdown.sh` applies this to `data/fireos/protected.txt`. `debloat.sh` applies the
same treatment to disabled packages whose process survived the disable.

## 4. DNS: optional network-level filtering

DumbTV does not run a DNS filter on the TV. The launcher has no `INTERNET`
permission, no VPN service, and no dependency on a local DNS-filter app.

For advanced network-level filtering, users can run Pi-hole on a separate,
always-on device and point the router or selected clients at it. This is entirely
optional and is not part of `configure.sh`, `all.sh`, or the launcher.

The repository keeps supplemental Amazon domains, optional Alexa endpoints,
Prime Video blocking endpoints, and diagnostic allowlist candidates under
`data/blocklists/pihole/`. Separate exports are provided for Pi-hole v6,
Pi-hole v5 with FTL 5.22+, and older v5 installations. Prime Video and Alexa
blocking are opt-in; the default Amazon list deliberately excludes those service
endpoints.

See [PI-HOLE.md](PI-HOLE.md) for list URLs, version compatibility, group assignment,
and Query Log-based troubleshooting. DNS filtering is external: `verify.sh`
does not inspect or alter the network resolver.

## 5. The home screen

On Android TV, `cmd package set-home-activity` changes the home app. On Fire OS it
prints `Success` and changes nothing: HOME still resolves to
`com.amazon.tv.launcher`, which Amazon protects, as it does
`com.amazon.firehomestarter`.

So dumbtv home ([launcher/](../launcher/README.md)) is built specifically for
the Fire TV Stick 4K (1st generation, AFTMM), Fire OS 6.7.1.1 / Android 7.1.2
(API 25). This fork does not ship the old cross-version accessibility takeover.

Amazon's protected launcher remains the system HOME handler. The launcher uses
one recovery service, Keeper:

- A boot receiver starts Keeper after the user is unlocked. Keeper runs as a
  foreground service.
- UsageStats foreground events are polled every 250 ms. The installer grants
  GET_USAGE_STATS through AppOps.
- If ADB grants READ_LOGS, Keeper also reads ActivityManager logcat for the exact
  Amazon Home activity becoming displayed or resumed. UsageStats and screen
  broadcasts remain active if READ_LOGS is unavailable.
- Keeper only intervenes after a positive Amazon Home foreground event and only
  while the display is interactive. It moves the existing DumbTV task to the
  foreground and verifies the result; a bounded direct activity-launch fallback
  handles a failed task move.
- Opening Amazon's own settings pauses takeover. It remains paused through known
  Amazon settings screens and resumes when a third-party app is opened.

Keeper does not reboot the device or force the display on. Recovery after long
deep standby still depends on Fire OS delivering usable foreground state/events;
that behaviour needs physical-device validation.

Holding HOME still opens Fire OS's quick menu, and Options can open Amazon's home
on purpose.

### HDMI inputs

An input tile hands Android's passthrough URI to the TV's own app, unless that app
is Amazon's Live TV, which claims every input and then shows its settings. In that
case the launcher shows the input itself with Android's public `TvView`: it asks
the TV's input service for a session and gives it the screen. Options › Open inputs
with chooses between the two; **Automatic** is the default.

## Not used, and why

| Not used | Why |
|---|---|
| `pm uninstall` for Amazon packages | disabling is enough and always reversible |
| `pm hide`, `pm suspend` | the shell lacks `MANAGE_USERS` and `SUSPEND_APPS` |
| `pm clear` | refused for protected packages |
| `dpm set-device-owner` | refused once the TV has an account |
| a hosts file | dm-verity keeps `/system` read-only |
| root | no supported unlock exists; see [WHY-NOT-REPLACE-OS.md](WHY-NOT-REPLACE-OS.md) |
