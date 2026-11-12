# Pi-hole v6: Amazon/Fire TV filtering without a TV-side VPN

This guide is for networks that already send client DNS through Pi-hole (and, optionally, Unbound). It deliberately avoids installing **personalDNSfilter** on the Fire TV. The on-device filter is optional and is now **off by default**.

## Add the upstream Amazon tracker list

Add this as a remote adlist in Pi-hole:

`https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/wildcard/native.amazon-onlydomains.txt`

It is HaGeZi's maintained Amazon-only domain list. Keep it as a remote source so Pi-hole can refresh it; do not copy an old snapshot into a static list.

## Add dumbtv's supplemental domains

Add the following raw URL as a second adlist:

`https://raw.githubusercontent.com/sodasoba1/dumbtv/main/data/blocklists/pihole/amazon-extra.txt`

The file is a Pi-hole-compatible plain domain list: one domain per line. It contains the supplemental entries from `data/blocklists/extra-hosts.txt`, with `device-messaging-na.amazon.com` deliberately omitted because dumbtv's Prime Video setting preserves that push service.

**Scope:** Pi-hole adlists apply to every client in the group to which they are assigned. If you only want these blocks on the Fire TV Stick, assign the lists to a Pi-hole group containing that stick rather than applying them to the default group. This list includes Amazon update hosts, so it can prevent Fire TV software updates while the entries remain blocked.

After adding the lists, update Gravity. Then use Pi-hole's Query Log to check actual requests and blocked responses.

## Prime Video exceptions

The five domains in [prime-video-allowlist-candidates.txt](../data/blocklists/pihole/prime-video-allowlist-candidates.txt) are candidates seen in the upstream HaGeZi feed and excluded by dumbtv's own composer when `PRIME=1`.

**Do not allowlist all five pre-emptively.** Only add a domain to Pi-hole's allowlist if the Query Log shows Pi-hole is blocking it and Prime Video is failing in a way that matches that request. Pi-hole's allowlist can override subscribed blocklists, but unnecessary exceptions weaken filtering.

`device-messaging-na.amazon.com` is not in the supplemental file. If Prime Video still fails, inspect the Query Log for that hostname and any other blocked Amazon push/CDN names before adding exceptions.

## Keep dumbtv's on-device DNS filter disabled

The default for `DNSFILTER` is now `0`. The normal `configure.sh` interview therefore defaults to **No** for the TV-side filter.

If you already created `dumbtv.conf` before this change, it may still contain `DNSFILTER=1`; defaults do not override an explicit saved value. Set it to:

```ini
PRIME=1
ALEXA=1
LAUNCHER=1
DNSFILTER=0
SYSTEM_DNS=0
```

Keep your other existing preferences in the file. With `DNSFILTER=0`, `scripts/dnsfilter.sh` exits before downloading the APK, installing it, granting VPN permissions, or writing VPN routes/configuration. The optional personalDNSfilter implementation remains available if someone explicitly opts in.

There is no TV-side VPN DNS ruleset to tune in this setup: Pi-hole receives the Fire TV's DNS queries directly through the router. Do not install personalDNSfilter as a second DNS layer, and do not set a separate DNS upstream in dumbtv.

## What this does not do

- It does not block YouTube in-stream ads; those use the same delivery infrastructure as video.
- It does not guarantee every Amazon telemetry hostname is covered. Use Pi-hole's Query Log to identify additional requests instead of guessing domains.
- It does not prove Prime Video compatibility on every Fire OS build. Verify playback after applying lists and review the Query Log if it fails.
