#!/usr/bin/env python3
"""Generate a comparison strip like the user's reference (r=0/200/300/400)."""
from PIL import Image, ImageDraw, ImageFilter

BODY_SIZE = 1024
DISPLAY_SIZE = 300
GAP = 60
MARGIN = 80
BG = (245, 245, 245, 255)
ICON_BG = (240, 239, 234, 255)
LINE_COLOR = (94, 92, 88, 255)
RADII = [0, 200, 300, 400]
LABELS = ['r=0', 'r=200', 'r=300', 'r=400']

W = MARGIN * 2 + len(RADII) * DISPLAY_SIZE + (len(RADII) - 1) * GAP
H = 520

img = Image.new('RGBA', (W, H), BG)
draw = ImageDraw.Draw(img)

for i, r in enumerate(RADII):
    display_x = MARGIN + i * (DISPLAY_SIZE + GAP)
    display_y = MARGIN

    # Render each icon at BODY_SIZE then downscale for crispness
    icon = Image.new('RGBA', (BODY_SIZE, BODY_SIZE), (0, 0, 0, 0))
    ic_draw = ImageDraw.Draw(icon)

    # shadow (rendered at BODY_SIZE scale)
    shadow = Image.new('RGBA', (BODY_SIZE + 120, BODY_SIZE + 120), (0, 0, 0, 0))
    sd = ImageDraw.Draw(shadow)
    sd.rounded_rectangle(
        [50, 50, 50 + BODY_SIZE, 50 + BODY_SIZE],
        radius=min(r, BODY_SIZE // 2),
        fill=(0, 0, 0, 55)
    )
    shadow = shadow.filter(ImageFilter.GaussianBlur(50))
    icon.paste(shadow, (-50, -50 + 24), shadow)

    # body
    ic_draw.rounded_rectangle(
        [0, 0, BODY_SIZE, BODY_SIZE],
        radius=min(r, BODY_SIZE // 2),
        fill=ICON_BG
    )

    # three lines
    line_left = int(BODY_SIZE * 0.22)
    line_y_center = BODY_SIZE // 2
    line_height = int(BODY_SIZE * 0.072)
    line_gap = int(BODY_SIZE * 0.135)
    line_widths = [int(BODY_SIZE * 0.48), int(BODY_SIZE * 0.64), int(BODY_SIZE * 0.36)]
    line_ys = [line_y_center - line_gap, line_y_center, line_y_center + line_gap]
    for lw, ly in zip(line_widths, line_ys):
        ic_draw.rounded_rectangle(
            [line_left, ly - line_height // 2, line_left + lw, ly + line_height // 2],
            radius=line_height // 2,
            fill=LINE_COLOR
        )

    # downscale
    icon_small = icon.resize((DISPLAY_SIZE, DISPLAY_SIZE), Image.LANCZOS)
    img.paste(icon_small, (display_x, display_y), icon_small)

    # label
    draw.text((display_x + DISPLAY_SIZE // 2, display_y + DISPLAY_SIZE + 30), LABELS[i],
              fill=(100, 100, 100, 255), anchor='mt')

img.save('icon_preview/radius_comparison.png')
print('Saved icon_preview/radius_comparison.png')
