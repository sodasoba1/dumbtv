# FAQ

**Contents:** [Safety](#safety) · [Your apps](#your-apps) · [Day to day](#day-to-day) ·
[Other TVs](#other-tvs) · [The project](#the-project)

## Safety

**Is this legal?**
Yes. You are changing settings and app states on a TV you own, through the debug
interface the manufacturer ships. Nothing bypasses a licence check, and no Amazon
code is modified or redistributed.

**Can this brick my TV?**
The scripts never write to `/system`, never remount it, never touch the bootloader,
and refuse to disable anything on `data/fireos/never-disable.txt`: the home screen,
settings, inputs, the tuner, remote pairing and Prime Video. The realistic worst
case is a TV that boots to Amazon's home screen, which `./scripts/restore.sh` or
`./scripts/launcher.sh` fixes.

**Does it need root?**
No. Everything runs as the `shell` user that ADB debugging gives you.

**How do I undo it?**
`./scripts/restore.sh`. It turns every package back on, clears the app-ops and
network rules, puts back the settings your TV had before (saved by
`preflight.sh`), disables the legacy filter only if it is the selected always-on VPN, and
returns HOME to Amazon. Apps you asked to have uninstalled come back from the Appstore.

**Does the project itself phone home?**
No. The computer downloads dumbtv home from this repository's releases (checked
against a pinned SHA-256), and SmartTube only if you request it. Optional Pi-hole
lists are fetched by Pi-hole itself from their published sources. Nothing is
uploaded. The launcher has no internet permission.

## Your apps

**Will it remove an app I watch?**
No. Amazon's ads, trackers and telemetry are disabled; apps are not touched. An app
is only removed when you name it, in `configure.sh` or with
`./scripts/configure.sh --remove <package>`.

**Will Prime Video work?**
Yes, by default. DumbTV does not configure DNS filtering, and its supplemental
list excludes Prime Video delivery endpoints. HaGeZi's upstream Amazon tracker list
contains five AIV delivery/CDN hosts; use the Prime Video compatibility allowlist
in [the Pi-hole guide](PI-HOLE.md) if playback is required. A separate opt-in blocklist
is available for people who deliberately want to block Prime Video.

**Will YouTube work?**
Yes. The default Pi-hole lists do not target Google or YouTube. The
YouTube app is only replaced if you ask for SmartTube; see
[YOUTUBE.md](YOUTUBE.md) for why YouTube's own ads cannot be blocked by DNS.

**Will the voice remote work?**
Yes, by default: Alexa is kept. If you answer no to Alexa in `configure.sh`
(`ALEXA=0`), Alexa and the app that owns the microphone are switched off and their
servers only blocked if you separately add the optional Alexa Pi-hole list; the buttons
and pairing keep working. To bring Alexa back:

```sh
./scripts/configure.sh --alexa 1   # then run ./scripts/restore.sh and ./scripts/all.sh
```

**Will the Appstore work?**
Yes. Its hosts are never blocked.

## Day to day

**Does it survive a reboot?**
Yes. Disabled packages and the home screen come back on their own. Pi-hole filtering
continues independently on the network.

**Does it survive an Amazon update?**
If you assigned the optional Pi-hole supplemental list, its update-host entries can
block Fire TV updates. Remove or disable that list in Pi-hole before updating. An
Amazon update can also re-enable packages and reset settings; run `./scripts/all.sh`
afterward. To update on purpose, run `./scripts/restore.sh`, let the TV update, then
`./scripts/all.sh`.

**Does it survive a factory reset?**
No. A factory reset undoes everything, including this. Run the scripts again.

**Do I need a computer every time?**
Only to run the scripts. After that the TV works on its own.

**Wi-Fi or USB?**
Wi-Fi: `adb connect <tv-ip>:5555`.

**Pressing HOME shows Amazon's home for a few seconds.**
It should land on dumbtv home in about a quarter of a second. Run
`./scripts/launcher.sh -y`; see [TROUBLESHOOTING.md](TROUBLESHOOTING.md).

## Other TVs

**Is this only for Fire TV?**
The scripts are for Fire OS: Fire TV sticks, cubes and Fire TV Edition
televisions. Android TV and Google TV can be cleaned the same way by hand, and the
catalog has reports from people who did. Samsung, LG, Roku, VIDAA, Vizio,
Panasonic and Philips Saphi give an owner no shell. [OTHER-TVS.md](OTHER-TVS.md)
has the detail for each.

**Can I install a different operating system?**
No. Every current TV has a signed boot chain and a verified system partition.
[WHY-NOT-REPLACE-OS.md](WHY-NOT-REPLACE-OS.md) explains why.

**Why not just use a Pi-hole?**
Pi-hole is a good optional addition if you want network-wide DNS filtering and can
configure your router or selected clients to use it. It is independent of DumbTV:
the cleanup and launcher work without Pi-hole, and DumbTV no longer runs an
on-device DNS filter.

## The project

**Why its own launcher?**
A home screen needs no internet access, no advertising ID and no permission to
install apps. dumbtv home is open source and asks for one permission on Fire OS 7
and 8: run at startup, used to start the Keeper after boot or an app update.
Check it yourself: `aapt2 dump permissions dumbtv-home.apk` lists three;
the other two are limited to Android 8.1 and earlier, for Fire OS 6's Keeper.

**Who made this?**
[Muzzammil](https://x.com/DevMuzzammil), with people who ran it on their own TVs
and sent fixes and device reports. They are credited in the
[README](../README.md#thanks).

**Can someone set it up for me?**
Yes. Tell us which TV you have at [dumbtv.org/help](https://dumbtv.org/help) and we
will reply by email.
