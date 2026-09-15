#!/usr/bin/env python3
"""Render the launcher icon (r=300, smooth corners, outer lifted shadow) into
every mipmap density as a crisp, proportionally-scaled PNG (no downscaling).

Each density is rendered from scratch at its true pixel size so the shadow
stays sharp instead of getting blurred away by a 1200->48 downscale.
"""
import os
from PIL import Image, ImageDraw, ImageFilter

# Reference canvas (the look is tuned here, everything scales off 1200px)
REF = 1200
REF_BODY = 1024
REF_RADIUS = 300

# Colors (inferred from the existing launcher icon)
BG_COLOR = (240, 239, 234, 255)        # warm off-white
LINE_COLOR = (94, 92, 88, 255)         # dark grey

# Outer "lifted" shadow (single tight layer, no soft ambient halo)
# offset down + slightly right so it sits clearly BELOW the icon
SHADOW_COLOR = (0, 0, 0, 255)
CONTACT_ALPHA = 100
REF_CONTACT_GROW = 22
REF_CONTACT_OFFSET_X = 14
REF_CONTACT_OFFSET_Y = 42
REF_CONTACT_BLUR = 42

DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}


def render_icon(size):
    f = size / REF
    body = round(REF_BODY * f)
    radius = round(REF_RADIUS * f)
    grow = max(1, round(REF_CONTACT_GROW * f))
    offset = (max(1, round(REF_CONTACT_OFFSET_X * f)), max(1, round(REF_CONTACT_OFFSET_Y * f)))
    blur = max(1, round(REF_CONTACT_BLUR * f))

    x0 = (size - body) // 2
    y0 = (size - body) // 2

    canvas = Image.new('RGBA', (size, size), (0, 0, 0, 0))

    # --- shadow layer (grown rect, pushed down, blurred) ---
    spread = grow
    sw = size + spread * 2
    sh = size + spread * 2
    shadow = Image.new('RGBA', (sw, sh), (0, 0, 0, 0))
    sd = ImageDraw.Draw(shadow)
    gx = x0 - grow + offset[0]
    gy = y0 - grow + offset[1]
    sd.rounded_rectangle(
        [gx, gy, gx + body + grow * 2, gy + body + grow * 2],
        radius=radius + grow,
        fill=(SHADOW_COLOR[0], SHADOW_COLOR[1], SHADOW_COLOR[2], CONTACT_ALPHA),
    )
    shadow = shadow.filter(ImageFilter.GaussianBlur(blur))
    canvas.paste(shadow, (-spread, -spread), shadow)

    # --- icon body ---
    draw = ImageDraw.Draw(canvas)
    draw.rounded_rectangle(
        [x0, y0, x0 + body, y0 + body],
        radius=radius,
        fill=BG_COLOR,
    )

    # --- three horizontal lines (left-aligned, centered vertically) ---
    line_left = x0 + int(body * 0.22)
    line_y_center = y0 + body // 2
    line_height = int(body * 0.072)
    line_gap = int(body * 0.135)
    line_widths = [int(body * 0.48), int(body * 0.64), int(body * 0.36)]
    line_ys = [line_y_center - line_gap, line_y_center, line_y_center + line_gap]
    for lw, ly in zip(line_widths, line_ys):
        draw.rounded_rectangle(
            [line_left, ly - line_height // 2, line_left + lw, ly + line_height // 2],
            radius=line_height // 2,
            fill=LINE_COLOR,
        )

    return canvas


def main():
    base = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'res')
    for density, size in DENSITIES.items():
        out_dir = os.path.join(base, f'mipmap-{density}')
        os.makedirs(out_dir, exist_ok=True)
        out_path = os.path.join(out_dir, 'ic_launcher.png')
        render_icon(size).save(out_path, optimize=True)
        print(f'{density:8s} {size}x{size} -> {out_path}')


if __name__ == '__main__':
    main()
