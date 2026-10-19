#!/usr/bin/env python3
"""Generate the Google Play feature graphic from app/src/main/res/drawable/ic_launcher.xml.

The launcher icon is a vector with four paths. Play's feature graphic has to be a flat
1024x500 raster with no alpha channel, so the paths are replayed here at whatever size the
graphic needs rather than upscaling a small PNG.

Run:  python3 play/make_feature_graphic.py
"""

from PIL import Image, ImageDraw, ImageFont
from pathlib import Path

W, H = 1024, 500

# Brand colours, taken from the vector drawable and ui/Theme.kt.
BG_TOP = (255, 255, 255)
BG_BOTTOM = (232, 240, 254)  # #E8F0FE
PAGE = (26, 115, 232)  # #1A73E8
FOLD = (138, 180, 248)  # #8AB4F8
RULE = (255, 255, 255)
TEXT = (32, 33, 36)  # #202124
MUTED = (95, 99, 104)  # #5F6368

FONT_DIR = "/usr/share/fonts/julietaula-montserrat-fonts"
BOLD = f"{FONT_DIR}/Montserrat-Bold.otf"
REGULAR = f"{FONT_DIR}/Montserrat-Regular.otf"

OUT = Path(__file__).with_name("feature-graphic-1024x500.png")


def draw_icon(size: int, canvas: Image.Image, cx: int, cy: int) -> None:
    """Replay ic_launcher.xml scaled from its 48x48 viewport."""
    draw = ImageDraw.Draw(canvas)
    s = size / 48.0

    def pt(x, y):
        return (cx - size / 2 + x * s, cy - size / 2 + y * s)

    draw.rounded_rectangle(
        [cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2],
        radius=size * 0.18, fill=BG_BOTTOM,
    )
    # The page, with its top-right corner folded.
    draw.polygon([pt(13, 7), pt(28, 7), pt(36, 15), pt(36, 41), pt(13, 41)], fill=PAGE)
    draw.polygon([pt(28, 7), pt(28, 15), pt(36, 15)], fill=FOLD)
    # Text rules. Stroke width in the vector is 2 of 48.
    w = max(2, round(2 * s))
    for y, x2 in ((22, 30), (28, 30), (34, 26)):
        draw.line([pt(18, y), pt(x2, y)], fill=RULE, width=w)


def main() -> None:
    # Flat vertical gradient. RGB, not RGBA: Play rejects graphics with an alpha channel.
    img = Image.new("RGB", (W, H), BG_TOP)
    grad = Image.new("RGB", (1, H))
    for y in range(H):
        t = y / (H - 1)
        grad.putpixel(
            (0, y),
            tuple(round(a + (b - a) * t) for a, b in zip(BG_TOP, BG_BOTTOM)),
        )
    img.paste(grad.resize((W, H)), (0, 0))

    # Centred composition. Play may crop the edges depending on where the graphic is
    # placed, so everything that matters sits within the middle. The vertical offsets are
    # chosen so the whole block spans 84..416, putting its centre on the canvas centre
    # rather than its top edge at the margin.
    icon = 168
    icon_cy = 168
    draw_icon(icon, img, W // 2, icon_cy)

    draw = ImageDraw.Draw(img)

    name_font = ImageFont.truetype(BOLD, 74)
    tag_font = ImageFont.truetype(REGULAR, 25)

    name = "Paper Docs"
    nbox = draw.textbbox((0, 0), name, font=name_font)
    draw.text(((W - (nbox[2] - nbox[0])) / 2 - nbox[0], 282), name, font=name_font, fill=TEXT)

    tagline = "Offline-first rich-text editing"
    tbox = draw.textbbox((0, 0), tagline, font=tag_font)
    draw.text(((W - (tbox[2] - tbox[0])) / 2 - tbox[0], 386), tagline, font=tag_font, fill=MUTED)

    img.save(OUT, "PNG")
    print(f"wrote {OUT} ({img.size[0]}x{img.size[1]}, mode={img.mode})")


if __name__ == "__main__":
    main()