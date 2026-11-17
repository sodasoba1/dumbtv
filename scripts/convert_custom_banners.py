#!/usr/bin/env python3
"""Render custom SVG banner sources as white-on-transparent 1024x512 PNGs.

Source:  custom/**/*.svg
Output: launcher/src/main/assets/custom-banners/**/*.png
"""
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from io import BytesIO
from pathlib import Path

import cairosvg
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SOURCE_DIR = ROOT / "custom"
OUTPUT_DIR = ROOT / "launcher" / "src" / "main" / "assets" / "custom-banners"

CANVAS_WIDTH = 1024
CANVAS_HEIGHT = 512
PADDING = 10
MAX_WIDTH = CANVAS_WIDTH - 2 * PADDING
MAX_HEIGHT = CANVAS_HEIGHT - 2 * PADDING
COLOUR_PROPERTIES = {"fill", "stroke", "color", "stop-color", "flood-color", "lighting-color"}
NON_COLOURS = {"none", "inherit", "initial", "unset", "transparent"}


def white_colour(value: str) -> str:
    value = value.strip()
    lower = value.lower()
    if not value or lower in NON_COLOURS or lower.startswith("url("):
        return value
    # currentColor is deliberately made white as well.
    return "#ffffff"


def rewrite_style(style: str) -> str:
    def replace(match: re.Match[str]) -> str:
        prop, value = match.group(1), match.group(2)
        if prop.lower() in COLOUR_PROPERTIES:
            return f"{prop}:{white_colour(value)}"
        return match.group(0)

    return re.sub(r"([\w-]+)\s*:\s*([^;{}]+)", replace, style)


def viewbox_ratio(root: ET.Element) -> float:
    viewbox = root.attrib.get("viewBox") or root.attrib.get("viewbox")
    if viewbox:
        values = re.split(r"[\s,]+", viewbox.strip())
        if len(values) == 4:
            try:
                width, height = float(values[2]), float(values[3])
                if width > 0 and height > 0:
                    return width / height
            except ValueError:
                pass

    def numeric(value: str | None) -> float | None:
        if not value:
            return None
        match = re.match(r"^\s*([0-9]*\.?[0-9]+)", value)
        return float(match.group(1)) if match else None

    width = numeric(root.attrib.get("width"))
    height = numeric(root.attrib.get("height"))
    if width and height and height > 0:
        return width / height
    return CANVAS_WIDTH / CANVAS_HEIGHT


def convert(svg_path: Path) -> Path:
    tree = ET.parse(svg_path)
    root = tree.getroot()

    # Recolour SVG vector paint while preserving opacity, masks and URL references.
    for element in root.iter():
        for attr_name, value in list(element.attrib.items()):
            local_name = attr_name.rsplit("}", 1)[-1].lower()
            if local_name in COLOUR_PROPERTIES:
                element.set(attr_name, white_colour(value))
            elif local_name == "style":
                element.set(attr_name, rewrite_style(value))

        if element.tag.rsplit("}", 1)[-1].lower() == "style" and element.text:
            element.text = rewrite_style(element.text)

    ratio = viewbox_ratio(root)
    svg_bytes = ET.tostring(root, encoding="utf-8", xml_declaration=True)

    # Specify only one render dimension so CairoSVG preserves the source aspect ratio.
    if ratio >= MAX_WIDTH / MAX_HEIGHT:
        rendered = cairosvg.svg2png(bytestring=svg_bytes, output_width=MAX_WIDTH)
    else:
        rendered = cairosvg.svg2png(bytestring=svg_bytes, output_height=MAX_HEIGHT)

    artwork = Image.open(BytesIO(rendered)).convert("RGBA")
    artwork.thumbnail((MAX_WIDTH, MAX_HEIGHT), Image.Resampling.LANCZOS)

    canvas = Image.new("RGBA", (CANVAS_WIDTH, CANVAS_HEIGHT), (0, 0, 0, 0))
    x = (CANVAS_WIDTH - artwork.width) // 2
    y = (CANVAS_HEIGHT - artwork.height) // 2
    canvas.alpha_composite(artwork, (x, y))

    relative = svg_path.relative_to(SOURCE_DIR).with_suffix(".png")
    destination = OUTPUT_DIR / relative
    destination.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(destination, format="PNG", optimize=True)
    return destination


def main() -> int:
    sources = sorted(SOURCE_DIR.rglob("*.svg"))
    if not sources:
        print(f"No SVG files found under {SOURCE_DIR}")
        return 0

    failures = []
    for source in sources:
        try:
            output = convert(source)
            print(f"OK  {source.relative_to(ROOT)} -> {output.relative_to(ROOT)}")
        except Exception as exc:
            failures.append((source, exc))
            print(f"FAIL {source.relative_to(ROOT)}: {exc}", file=sys.stderr)

    if failures:
        print(f"{len(failures)} SVG conversion(s) failed.", file=sys.stderr)
        return 1

    print(f"Converted {len(sources)} SVG file(s) to {CANVAS_WIDTH}x{CANVAS_HEIGHT} transparent PNGs.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
