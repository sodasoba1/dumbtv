# Troubleshooting

Start with `./scripts/verify.sh`. It checks the home screen and cleanup settings;
DNS filtering is external and should be checked in Pi-hole's Query Log.

To put the TV back the way it was: `./scripts/restore.sh`.

**Contents:** [Home screen](#home-screen) · [Inputs](#inputs) · [Pi-hole DNS](#pihole-dns-filtering) ·
[Packages and apps](#packages-and-apps) · [Updates](#updates) ·
[Fire OS 6](#fire-os-6) · [Testing like a remote](#testing-like-a-remote)

## Home screen

### Amazon's home screen came back

This launcher build supports only the AFTMM on Fire OS 6 / Android API 25.
Keeper must be running as a foreground service, and UsageStats access must be
granted. Check:

```sh
adb shell dumpsys activity services tv.dumbtv.home
adb shell appops get tv.dumbtv.home GET_USAGE_STATS
adb shell pm check-permission android.permission.READ_LOGS tv.dumbtv.home
```

READ_LOGS is an additional signal, not a hard requirement: if Fire OS refuses
it, Keeper still uses UsageStats and screen on/off broadcasts. Re-arm the
launcher with `./scripts/launcher.sh -y` and then run `./scripts/verify.sh`.

### Amazon's Settings stays on screen

This is intentional. Opening Amazon's own settings pauses takeover so that
navigation is not interrupted. Opening a third-party app clears the pause and
allows Keeper to resume recovery.

### Settings bounces back to the home screen

On Fire OS 6, the generic Android Settings action can open a stub that forwards
to Amazon's home. The launcher's Settings button uses the Fire OS 6 section list
instead. From ADB, open the TV settings entry point with:

```sh
adb shell am start -a android.intent.action.MAIN -c android.intent.category.LEANBACK_SETTINGS
```

### A blank screen for about 15 seconds after boot

That is Android's FallbackHome while the system starts. Nothing is broken.

### HOME from an HDMI input takes about five seconds

On Fire TV Edition televisions Amazon's Live TV app holds the display while it
closes. Nothing outside that app can shorten it. Open inputs with the launcher's
own screen instead (Options › Open inputs with › Automatic or dumbtv home): it
releases the picture about 0.4 s after HOME.

### An HDMI tile opens Live TV settings instead of the input

Amazon's Live TV claims every input and then shows its settings. Set Options ›
Open inputs with › dumbtv home to show the input directly.

## Inputs

### The TV sleeps while something is playing on HDMI

The picture goes black after a few minutes and the TV behaves as if nothing is
playing: it goes to sleep and saves energy. Fire OS counts a passthrough input
as no activity. Nothing plays through a media session, and the window that
shows the picture does not ask to stay awake, so the screen-off timer runs
(300000 ms is five minutes) and the TV puts itself to sleep.

dumbtv home 1.3 and later keep the panel awake while an input shows a picture,
and let it sleep again once the input reports that there is no signal. Check
which release is on the TV:

```sh
adb shell dumpsys package tv.dumbtv.home | grep versionName
```

An older launcher says `1.2` or lower: run `./scripts/launcher.sh -y` to
install the current release. `verify.sh` reports the same thing.

If the input reports "No signal" while something is plainly playing, the input
service has not locked the port. On a MediaTek television that is what
`HdmiInputService: checkVideoAvaliable: no signal` in the log means; the
picture comes when the source wakes up.

### The screen goes off after five minutes on any input

That is the screen-off timeout, a TV setting: Settings > Preferences > Screen
Saver > Screen Off. Over adb it is `settings get system screen_off_timeout`,
in milliseconds.

## Pi-hole DNS filtering

DNS filtering is external to DumbTV. The launcher does not install a VPN or
intercept DNS requests.

- **The Fire TV does not appear in Pi-hole's Query Log.** Check the DNS server
  advertised by your router and confirm the Fire TV is using Pi-hole rather than
  an ISP, public, or IPv6 resolver.
- **The list is not blocking a hostname.** Confirm the correct version-specific
  list URL is subscribed, then update Gravity. For FTL earlier than 5.22 use
  the legacy plain-domain list; Adblock/ABP rules require FTL 5.22 or later.
- **An app stopped working.** Find the exact blocked hostname in Query Log and
  allow only that entry if the evidence shows it is required. Do not broadly
  allowlist Amazon parent domains.
- **Prime Video stopped working.** Make sure the opt-in Prime Video blocklist
  has not been assigned to the Fire TV group. Review blocked requests before
  adding any exception.

See [PI-HOLE.md](PI-HOLE.md) for version-specific setup and list URLs.

## Packages and apps

### "Unable to Detect Your Remote" covers everything

`com.amazon.vizzini` was disabled. It is a persistent system app that draws that
window while disabled, and taps cannot dismiss it. It is on `never-disable.txt`.

```sh
adb shell pm enable --user 0 com.amazon.vizzini
adb reboot
```

### Prime Video does not open

`com.amazon.firebat` is Prime Video on Fire OS 6, 7 and 8, despite the name. It is
on `never-disable.txt` now; a TV debloated by an older version can get it back
with `./scripts/restore.sh` or `adb shell pm enable --user 0 com.amazon.firebat`.
If Pi-hole is in use, check the Query Log and make sure the opt-in Prime Video blocklist is not assigned to this device.

### Prime Video fails only when Pi-hole filtering is enabled

Check that the opt-in Prime Video blocklist is not assigned to the Fire TV's Pi-hole group. Inspect the Query Log before adding any allowlist exception; the old on-device VPN workaround no longer exists.

### The Fire TV phone app will not connect

The app finds the TV but never pairs, and no PIN appears on screen. logcat shows
`ActivityNotFoundException ... com.amazon.storm.lightning.tutorial.authentication.SHOW`.
Despite its name, `com.amazon.storm.lightning.tutorial` holds the PIN screen the app
pairs with, on Fire OS 6, 7 and 8. It is on `never-disable.txt` now; a TV debloated by an
older version gets it back with `adb shell pm enable --user 0 com.amazon.storm.lightning.tutorial`.

### A package I disabled is still running

Persistent system apps keep their process after `pm disable-user`, and
`am force-stop` does not end it. `debloat.sh` has a second pass for these: it
takes away their background work, and their background data on metered networks.
On Wi-Fi and Ethernet, an optional Pi-hole list can block their DNS requests.

### `debloat.sh` reports `PROTECTED`

Amazon refuses to let the shell disable that package. Which packages are protected
changes between builds. Add it to `data/fireos/protected.txt`; `lockdown.sh` takes
away its background work and data. Pi-hole can optionally block its DNS requests.

### Some packages come back after a reboot

`com.amazon.dummy.settings`, `com.android.traceur` and `com.amazon.vizzini`
re-enable themselves on some builds. Run `./scripts/debloat.sh` again; it is safe
to repeat.

## Updates

### An Amazon update undid the cleanup

Updates re-enable packages and reset settings. `verify.sh` names both: a `warn
packages` line lists cleanup packages that are on again, and a `warn settings` line
lists ad-tracking and usage settings back at Amazon's values. `./scripts/debloat.sh -y`
puts them back. The updater itself is protected. If you separately assigned the optional Pi-hole
supplemental list, it also blocks `softwareupdates.amazon.com` and download hosts.
That network rule is independent of the TV cleanup and can be removed in Pi-hole.

To install an update on purpose: `./scripts/restore.sh`, let the TV update, then
run `./scripts/all.sh`.

## Fire OS 6

The launcher target is the Fire TV Stick 4K (AFTMM), Fire OS 6.7.1.1 / API 25.
The scripts select `data/fireos6/` automatically.

- **Keeper is not running.** Re-run `./scripts/launcher.sh -y`; then check
  `dumpsys activity services tv.dumbtv.home`.
- **UsageStats is unavailable.** The installer grants `GET_USAGE_STATS`; check
  it with `appops get tv.dumbtv.home GET_USAGE_STATS`.
- **READ_LOGS is denied.** This is optional. Keeper continues with UsageStats
  and screen broadcasts, but loses the additional ActivityManager signal.
- **`monkey` launches nothing** and says `SYS_KEYS has no physical keys` when
  the remote is asleep. Use `am start -n`, or pass `--pct-syskeys 0`.

## Fire OS 7 and 8

The wider debloat scripts still support these Fire OS versions, but this fork's
launcher APK and installer are intentionally restricted to API 25 / Fire OS 6.
Do not use this launcher build on Fire OS 7 or 8.

## Testing like a remote

`adb shell input keyevent HOME` skips accessibility filters, so it proves nothing
about the real remote. Write to the remote's input device instead (find `N` with
`getevent -pl`):

```sh
adb shell sendevent /dev/input/eventN 1 172 1
adb shell sendevent /dev/input/eventN 0 0 0
adb shell sendevent /dev/input/eventN 1 172 0
adb shell sendevent /dev/input/eventN 0 0 0
```
