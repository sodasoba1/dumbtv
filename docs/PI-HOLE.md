# Advanced DNS filtering with Pi-hole

DumbTV no longer installs or runs a DNS filter on the Fire TV. The launcher has no
network permission and does not create a VPN. DNS filtering is an optional,
network-level advanced setup: install Pi-hole on an always-on host, point the
router or selected clients at it, then subscribe to the lists below.

This is separate from the normal DumbTV cleanup. The normal setup works without
Pi-hole, and Pi-hole can be used without installing DumbTV.

## Before adding lists

- Start with the default Amazon tracker list and DumbTV supplemental list only.
- Add the Prime Video compatibility allowlist to preserve the original default behaviour while using the HaGeZi Amazon tracker list.
- Do not add the opt-in Prime Video blocklist unless you deliberately want playback blocked.
- Pi-hole lists apply to the clients assigned to them. Create a group containing
  only the Fire TV devices if you do not want these rules affecting the whole network.
- Make sure the Fire TV actually uses Pi-hole for DNS. If it uses another resolver,
  Pi-hole cannot filter its requests.
- Keep a way to reach Pi-hole locally if a list causes problems.

## Blocklists

### Recommended baseline

Add these as subscribed blocklists:

| List | Purpose |
|---|---|
| [HaGeZi Amazon native trackers](https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/native.amazon.txt) | Maintained Amazon device, app and OS tracker domains |
| [DumbTV Amazon extras](https://raw.githubusercontent.com/sodasoba1/dumbtv/main/data/blocklists/pihole/v6/amazon-extra.txt) | Supplemental telemetry, advertising and update hosts found in Fire TV components |

The HaGeZi URL uses Pi-hole's Adblock/ABP format, not the legacy wildcard-domain format used by the old on-device filter. It is suitable for Pi-hole FTL 5.22 and later, including Pi-hole v6. <https://github.com/hagezi/dns-blocklists>

### Optional lists

Only add these if you understand the effect:

| List | Purpose and risk |
|---|---|
| [Alexa endpoints](https://raw.githubusercontent.com/sodasoba1/dumbtv/main/data/blocklists/pihole/v6/alexa.txt) | Blocks Alexa voice and device endpoints. The voice remote and Alexa features will stop working. |
| [Prime Video endpoints](https://raw.githubusercontent.com/sodasoba1/dumbtv/main/data/blocklists/pihole/v6/prime-video.txt) | Intentionally blocks Prime Video delivery and device-messaging endpoints. Prime Video will stop working, and shared Amazon functions may also be affected. |

Do not add the Prime Video blocklist to the baseline. It is an explicit opt-in list; remove the compatibility allowlist first if you intend to block those endpoints. On FTL earlier than 5.22, use wildcard deny entries for the two AIV parent domains as described below to cover subdomains.

## Pi-hole version compatibility

Check your FTL version with `pihole -v`. The domain policy is the same; list syntax and allowlist management differ by FTL/Pi-hole generation.

### Pi-hole v6

Use the Adblock-format files in `data/blocklists/pihole/v6/`:

- [Amazon extras](https://raw.githubusercontent.com/sodasoba1/dumbtv/main/data/blocklists/pihole/v6/amazon-extra.txt)
- [Alexa](https://raw.githubusercontent.com/sodasoba1/dumbtv/main/data/blocklists/pihole/v6/alexa.txt)
- [Prime Video (opt-in)](https://raw.githubusercontent.com/sodasoba1/dumbtv/main/data/blocklists/pihole/v6/prime-video.txt)

In the admin interface, open **Settings → Lists**, add the raw URL, and save.
Then update Gravity from **Tools → Update Gravity**, or run:

```sh
sudo pihole -g
```

Pi-hole v6 supports subscribed allowlists. To preserve Prime Video playback with
the current HaGeZi Amazon list, subscribe to the [Prime Video compatibility
allowlist](https://raw.githubusercontent.com/sodasoba1/dumbtv/main/data/blocklists/pihole/prime-video-allowlist.txt)
as an allowlist in **Settings → Lists** (choose the allowlist type, not denylist),
then update Gravity. It contains the five AIV delivery/CDN hosts that the old DumbTV
`PRIME=1` logic deliberately kept out of its blocklist. Check the Query Log if
Prime Video still fails; do not add broad Amazon wildcard exceptions.

### Pi-hole v5 with FTL 5.22 or later

Use the Adblock-format exports in `data/blocklists/pihole/v5/` (FTL 5.22+):

- [Amazon extras](https://raw.githubusercontent.com/sodasoba1/dumbtv/main/data/blocklists/pihole/v5/amazon-extra.txt)
- [Alexa](https://raw.githubusercontent.com/sodasoba1/dumbtv/main/data/blocklists/pihole/v5/alexa.txt)
- [Prime Video (opt-in)](https://raw.githubusercontent.com/sodasoba1/dumbtv/main/data/blocklists/pihole/v5/prime-video.txt)

FTL 5.22 added Adblock/ABP list support. In the admin interface, open
**Group Management → Adlists**, add the raw URL, and update Gravity:

```sh
sudo pihole -g
```

### Older Pi-hole v5 installations (FTL earlier than 5.22)

Older FTL releases do not parse Adblock/ABP rules. Use the plain-domain files in
`data/blocklists/pihole/v5-legacy/` instead, and use HaGeZi's legacy enumerated
subdomain list rather than its Adblock URL:

- [HaGeZi Amazon native trackers (legacy subdomain format)](https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists-legacy@latest/domains/native.amazon.txt)
- [DumbTV Amazon extras](https://raw.githubusercontent.com/sodasoba1/dumbtv/main/data/blocklists/pihole/v5-legacy/amazon-extra.txt)
- [Alexa](https://raw.githubusercontent.com/sodasoba1/dumbtv/main/data/blocklists/pihole/v5-legacy/alexa.txt)
- [Prime Video (opt-in)](https://raw.githubusercontent.com/sodasoba1/dumbtv/main/data/blocklists/pihole/v5-legacy/prime-video.txt)

These are valid domain-only adlists. DumbTV's supplemental list blocks the listed
hostnames only; the HaGeZi legacy list explicitly enumerates known subdomains but
cannot cover newly created or dynamic subdomains.
For wildcard coverage, add selected parent domains as wildcard deny entries using
Pi-hole's CLI, for example:

```sh
sudo pihole --wild amazon-adsystem.com
```

Do this selectively: wildcarding a broad Amazon parent domain can break legitimate
Amazon services. To make the opt-in Prime Video list cover its delivery subdomains
on legacy FTL, use only these specific wildcard entries:

```sh
sudo pihole --wild aiv-delivery.net
sudo pihole --wild aiv-cdn.net
sudo pihole --wild device-messaging-na.amazon.com
```

Check the Query Log before adding any other wildcard.

## Allowlists and exceptions

An allowlist is not another blocklist. Use the Prime Video compatibility list
only when you want playback preserved alongside the HaGeZi Amazon tracker list.
Other allowlist candidates are diagnostic references: add them only when Query Log
evidence shows a required hostname is being blocked.

### Pi-hole v6 subscribed allowlist

[Prime Video compatibility allowlist](https://raw.githubusercontent.com/sodasoba1/dumbtv/main/data/blocklists/pihole/prime-video-allowlist.txt)
is a plain domain list that v6 can subscribe to as an allowlist. It preserves the
five AIV delivery/CDN hostnames deliberately excluded by the former local filter.
Add it alongside the HaGeZi Amazon tracker list if Prime Video must remain usable.

Pi-hole v6 supports subscribed allowlists; exact and wildcard allowlist entries
take precedence over subscribed blocklists. See the
[Pi-hole domain database documentation](https://docs.pi-hole.net/database/domain-database/).

### Pi-hole v5 manual allowlist

Pi-hole v5 does not support subscribed allowlist URLs. To preserve the original
Prime Video default, import the five exact compatibility entries individually:

```sh
while IFS= read -r domain; do
  case "$domain" in ''|\#*) continue ;; esac
  sudo pihole -w "$domain"
done < data/blocklists/pihole/prime-video-allowlist.txt
```

These are exact host exceptions, not broad wildcards. If a different hostname is
blocked, inspect the Query Log and add only the proven requirement.

The [essential-service candidates](https://raw.githubusercontent.com/sodasoba1/dumbtv/main/data/blocklists/pihole/allowed-essential.txt)
are diagnostic references, not a list to import pre-emptively. If the Query Log
proves one of these exact domains is being blocked and a required feature fails:

- **Pi-hole v6:** add the raw URL under **Settings → Lists** and choose the allowlist type.
- **Pi-hole v5:** add only the proven domain(s) manually with `sudo pihole -w domain`.

YouTube, Appstore artwork and app delivery endpoints are intentionally not part of
the default blocklists.

## Pre-installation DNS audit

Run the DNS audit **before** running the DumbTV installation. It is a separate,
read-only diagnostic like the device preflight: it does not change Fire OS,
Pi-hole, router settings, packages, VPNs or DNS configuration.

From the repository root, with ADB debugging enabled and the Fire Stick connected:

```sh
bash ./scripts/dns-preflight.sh
```

Optional labels let the report identify known resolver addresses:

```sh
bash ./scripts/dns-preflight.sh --pihole-ip <PIHOLE_IP> --unbound-ip <UNBOUND_IP>
```

The script reads Android's `net.dns1`, `net.dns2` and DHCP DNS properties.
It prints the actual IP addresses. It labels an address Pi-hole or Unbound only
when it matches an address you supplied; otherwise it reports the IP without
guessing. If the Fire Stick points at the router, the report says router/gateway:
the router may forward to Pi-hole, but that upstream cannot be identified from
the Fire Stick's resolver property alone. In the common Pi-hole → Unbound
arrangement, the Fire Stick sees Pi-hole; Unbound is Pi-hole's upstream.

The audit queries every domain in DumbTV's local source lists, two representative
domains from HaGeZi's subscribed Amazon tracker list, and the required allowlist
controls from the Fire Stick itself. The HaGeZi probes are not a separate list to
subscribe; they are sample hostnames used to check that the upstream subscription
is taking effect. Use `--no-hagezi` if you have not subscribed to HaGeZi. Each row shows the expected
policy, hostname, associated app/service and observed DNS result. By default,
Alexa and Prime Video are expected to remain available. If you intend to block
those services, select the matching profile:

```sh
bash ./scripts/dns-preflight.sh --block-alexa
bash ./scripts/dns-preflight.sh --block-prime-video
bash ./scripts/dns-preflight.sh --block-alexa --block-prime-video
bash ./scripts/dns-preflight.sh --no-hagezi
```

The report uses three outcomes:

- **OK:** the observed DNS result matches the selected policy.
- **MISMATCH:** a domain expected to be blocked resolves, or an expected
  allowed domain returns a blocked-style response.
- **UNKNOWN:** the Fire Stick lookup could not be classified (for example,
  network/DNS timeout).

A blocked-style DNS response is not, by itself, proof that Pi-hole blocked it:
the domain might naturally return NXDOMAIN, or another resolver/filter might
have answered. Check Pi-hole's Query Log at the test time to confirm the
hostname and blocking status. DNS caching can also affect individual results.
The audit checks exact hostnames; the two HaGeZi probes are representative only.
It does not prove every entry in HaGeZi's subscribed upstream list is active or
test wildcard matching.

### Where the Pi-hole lists live

The source files and generated Pi-hole exports are committed in the repository:

| Purpose | Repository path |
|---|---|
| Baseline supplemental Amazon domains | `data/blocklists/extra-hosts.txt` |
| Optional Alexa domains | `data/blocklists/alexa-hosts.txt` |
| Optional Prime Video block domains | `data/blocklists/prime-video-hosts.txt` |
| HaGeZi upstream audit probes (not a blocklist) | `data/blocklists/hagezi-probes.txt` |
| Pi-hole v6 exports | `data/blocklists/pihole/v6/` |
| Pi-hole v5 FTL 5.22+ exports | `data/blocklists/pihole/v5/` |
| Older Pi-hole v5 exports | `data/blocklists/pihole/v5-legacy/` |
| Prime Video compatibility allowlist | `data/blocklists/pihole/prime-video-allowlist.txt` |
| Domain purpose, confidence and evidence | `data/blocklists/domain-purpose.tsv` |
| Researched but not enabled candidates | `data/blocklists/research-candidates.tsv` |

The Pi-hole admin interface subscribes to the raw GitHub URLs documented above.
For v6, add them under **Settings → Lists**, selecting denylist or allowlist as
appropriate. For v5, use **Group Management → Adlists** for blocklists; v5 does
not support subscribed allowlist URLs, so add the five Prime Video compatibility
entries individually with `pihole -w <domain>`. Then update Gravity with
`sudo pihole -g`. Select the matching version directory: v6 and FTL 5.22+
use Adblock/ABP exports; older FTL uses plain-domain exports.

### Domain purpose and confidence

Each active domain has an entry in `domain-purpose.tsv`. The report can therefore
show the app/service association without pretending every internal Amazon
hostname is fully understood. Confidence values mean:

- **confirmed:** supported by official documentation or independent technical
  research that directly identifies the endpoint/service.
- **community:** appears in maintained community Fire TV lists or corroborating
  network reports; exact function may still be uncertain.
- **inferred:** grouped by source-list context or hostname pattern, not proven.
- **unknown:** no reliable public endpoint-specific explanation found. The
  report uses the generic description "Amazon service/analytics endpoint;
  precise purpose undocumented".

The existing list does not contain a domain that can confidently be called an
ACR-only endpoint. Device-metrics and Unagi hosts are associated with device
telemetry in community research, but that does not prove that a particular
hostname carries Automatic Content Recognition data.

### Additional online candidates (not enabled)

Research found further Fire TV-associated domains that are not in the active
DumbTV lists. They are recorded in `research-candidates.tsv`, but are **not
automatically tested as blocked or added to Pi-hole**. Examples include
`minerva.devices.a2z.com`, `insights.video.a2z.com`, and the Fire TV Bugsnag
notification hostname. Community sources group some of these with telemetry,
possible ACR or crash reporting, but the exact function and device-specific
impact are not established. They should only be promoted after observing them
in this Fire Stick's Pi-hole Query Log and testing the effect on apps.

## Limitations

- DNS filtering cannot reliably remove YouTube in-stream ads because video and ad
  delivery share infrastructure.
- Blocking update hosts can prevent Fire TV software updates. This is deliberate
  in the supplemental list; users should understand that trade-off before enabling it.
- Amazon uses shared infrastructure. A list labelled Prime Video may affect other
  Amazon services that use the same endpoints.
- Lists are maintained snapshots or upstream subscriptions, not a guarantee that
  every Amazon tracking endpoint is covered.
- To change the local source domains, edit `data/blocklists/extra-hosts.txt`,
  `alexa-hosts.txt`, or `prime-video-hosts.txt`, then run
  `python3 scripts/export-pihole-lists.py --write`. CI checks the generated files.
