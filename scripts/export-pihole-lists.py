#!/usr/bin/env python3
"""Generate Pi-hole list exports from the maintained source domain lists.

Use --write after changing a source list; CI uses --check to catch stale exports.
No network access or third-party Python packages are required.
"""
from argparse import ArgumentParser
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "data" / "blocklists"
OUT = BASE / "pihole"
DOMAIN = re.compile(
    r"^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$"
)


def read_domains(path: Path) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        value = raw.split("#", 1)[0].strip().lower()
        if not value:
            continue
        if not DOMAIN.fullmatch(value):
            raise ValueError(f"{path.relative_to(ROOT)}:{number}: invalid domain {value!r}")
        if value in seen:
            raise ValueError(f"{path.relative_to(ROOT)}:{number}: duplicate domain {value!r}")
        seen.add(value)
        result.append(value)
    if not result:
        raise ValueError(f"{path.relative_to(ROOT)}: no domains found")
    return result


def render_plain(title: str, domains: list[str]) -> str:
    return (
        f"# {title}\n"
        "# Plain domain format for Pi-hole FTL before 5.22.\n"
        + "\n".join(domains)
        + "\n"
    )


def render_abp(title: str, domains: list[str]) -> str:
    return (
        f"! {title}\n"
        "! Pi-hole Adblock/ABP format; requires FTL 5.22 or later.\n"
        "! Matches the listed domain and its subdomains.\n"
        + "\n".join(f"||{domain}^" for domain in domains)
        + "\n"
    )


def outputs() -> dict[Path, str]:
    sources = {
        "amazon-extra": BASE / "extra-hosts.txt",
        "alexa": BASE / "alexa-hosts.txt",
        "prime-video": BASE / "prime-video-hosts.txt",
    }
    data = {name: read_domains(path) for name, path in sources.items()}
    result: dict[Path, str] = {
        OUT / "amazon-extra.txt": render_plain(
            "DumbTV supplemental Amazon blocklist; versioned exports are below.", data["amazon-extra"]
        )
    }
    for version in ("v5", "v6"):
        for name, domains in data.items():
            result[OUT / version / f"{name}.txt"] = render_abp(
                f"DumbTV {name} list for Pi-hole {version}.", domains
            )
    for name, domains in data.items():
        result[OUT / "v5-legacy" / f"{name}.txt"] = render_plain(
            f"DumbTV {name} list for legacy Pi-hole v5.", domains
        )
    return result


def main() -> int:
    parser = ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--write", action="store_true", help="regenerate the committed exports")
    mode.add_argument("--check", action="store_true", help="fail if committed exports are stale (default)")
    args = parser.parse_args()

    stale: list[str] = []
    for path, expected in outputs().items():
        if args.write:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(expected, encoding="utf-8")
        elif not path.exists() or path.read_text(encoding="utf-8") != expected:
            stale.append(str(path.relative_to(ROOT)))

    if stale:
        print("Pi-hole exports are stale; run: python3 scripts/export-pihole-lists.py --write", file=sys.stderr)
        print("\n".join(stale), file=sys.stderr)
        return 1
    print("Pi-hole exports are current.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
