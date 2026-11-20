"""Render the Fairy reference SVG into Android launcher resources.

Requires CairoSVG and Pillow. Run from the repository root:
    python tools/generate-launcher-icon.py
"""

from io import BytesIO
from pathlib import Path
import xml.etree.ElementTree as ET

import cairosvg
from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "references/fairy-zzz-plugin/icon.svg"
RES = ROOT / "app/src/main/res"
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
MASTER_SIZE = 1024
SVG_NS = "http://www.w3.org/2000/svg"


def render(svg: bytes) -> Image.Image:
    png = cairosvg.svg2png(bytestring=svg, output_width=MASTER_SIZE, output_height=MASTER_SIZE)
    return Image.open(BytesIO(png)).convert("RGBA")


def save_sizes(image: Image.Image, name: str) -> None:
    for density, size in SIZES.items():
        directory = RES / f"mipmap-{density}"
        directory.mkdir(parents=True, exist_ok=True)
        image.resize((size, size), Image.Resampling.LANCZOS).save(directory / name)


def main() -> None:
    svg = SOURCE.read_bytes()
    root = ET.fromstring(svg)
    # Keep only the eye and leave generous white space around it.
    for child in list(root):
        if child.tag == f"{{{SVG_NS}}}rect":
            root.remove(child)
    root.set("viewBox", "-14 -14 92 92")
    foreground = render(ET.tostring(root))
    legacy = Image.new("RGBA", (MASTER_SIZE, MASTER_SIZE), "white")
    legacy.alpha_composite(foreground)
    save_sizes(legacy, "ic_launcher.png")
    save_sizes(foreground, "ic_launcher_foreground.png")


if __name__ == "__main__":
    main()
