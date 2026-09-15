#!/usr/bin/env python3
"""Generate an icon preview with r=300, smooth corners, and floating shadow."""
import os
from PIL import Image, ImageDraw, ImageFilter

OUT_DIR = os.path.dirname(__file__)

# Canvas size and icon body size
W, H = 1200, 1200          # larger canvas so shadows are not clipped
BODY_SIZE = 1024
RADIUS = 300

# Center the body
x0 = (W - BODY_SIZE) // 2
y0 = (H - BODY_SIZE) // 2

# Colors (inferred from the existing launcher icon)
BG_COLOR = (240, 239, 234, 255)        # warm off-white
LINE_COLOR = (94, 92, 88, 255)         # dark grey

# Pure OUTER floating shadow (single layer):
#   offset downward and slightly right so the shadow sits clearly BELOW
#   the icon, like a lifted card on a table. The icon body is drawn on top,
#   so only the part of the shadow that spills OUTSIDE the body is visible.
SHADOW_COLOR = (0, 0, 0, 255)

CONTACT_ALPHA = 100        # soft, natural drop shadow
CONTACT_GROW = 22          # modest spill past the sides/bottom
CONTACT_OFFSET = (14, 42)  # right + down, bottom-weighted
CONTACT_BLUR = 42          # softer diffuse shadow like the reference


def make_shadow_layer(color, alpha, grow, offset, blur):
    """Render one blurred rounded-rect shadow layer (grown by `grow` on each side)."""
    spread = grow
    sw = W + spread * 2
    sh = H + spread * 2
    img = Image.new('RGBA', (sw, sh), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    gx = x0 - grow + offset[0]
    gy = y0 - grow + offset[1]
    draw.rounded_rectangle(
        [gx, gy, gx + BODY_SIZE + grow * 2, gy + BODY_SIZE + grow * 2],
        radius=RADIUS + grow,
        fill=(color[0], color[1], color[2], alpha)
    )
    return img.filter(ImageFilter.GaussianBlur(blur)), spread


# ------------------------------------------------------------------
# 1. Shadow layer
# ------------------------------------------------------------------
shadow, sh_spread = make_shadow_layer(SHADOW_COLOR, CONTACT_ALPHA, CONTACT_GROW, CONTACT_OFFSET, CONTACT_BLUR)

# ------------------------------------------------------------------
# 2. Main canvas
# ------------------------------------------------------------------
canvas = Image.new('RGBA', (W, H), (0, 0, 0, 0))

# Composite shadow onto canvas
canvas.paste(shadow, (-sh_spread, -sh_spread), shadow)

# ------------------------------------------------------------------
# 3. Icon body
# ------------------------------------------------------------------
draw = ImageDraw.Draw(canvas)
draw.rounded_rectangle(
    [x0, y0, x0 + BODY_SIZE, y0 + BODY_SIZE],
    radius=RADIUS,
    fill=BG_COLOR
)

# ------------------------------------------------------------------
# 4. Three horizontal lines (left-aligned, centered vertically)
# ------------------------------------------------------------------
line_left = x0 + int(BODY_SIZE * 0.22)
line_y_center = y0 + BODY_SIZE // 2
line_height = int(BODY_SIZE * 0.072)
line_gap = int(BODY_SIZE * 0.135)

line_widths = [
    int(BODY_SIZE * 0.48),   # top
    int(BODY_SIZE * 0.64),   # middle (longest)
    int(BODY_SIZE * 0.36),   # bottom (shortest)
]

line_ys = [
    line_y_center - line_gap,
    line_y_center,
    line_y_center + line_gap,
]

for lw, ly in zip(line_widths, line_ys):
    draw.rounded_rectangle(
        [line_left, ly - line_height // 2, line_left + lw, ly + line_height // 2],
        radius=line_height // 2,
        fill=LINE_COLOR
    )

# ------------------------------------------------------------------
# 5. Save transparent version
# ------------------------------------------------------------------
canvas.save(os.path.join(OUT_DIR, 'icon_r300_shadow.png'))

# ------------------------------------------------------------------
# 6. Light flat background (so the outer shadow is clearly visible)
# ------------------------------------------------------------------
PREVIEW_BG = (245, 245, 245, 255)
preview = Image.new('RGBA', (W, H), PREVIEW_BG)
preview.paste(canvas, (0, 0), canvas)
preview.save(os.path.join(OUT_DIR, 'icon_r300_shadow_on_bg.png'))

# ------------------------------------------------------------------
# 7. "Wallpaper" background -> strongest floating feel, shadow clearly
#    sits OUTSIDE the icon shape.
# ------------------------------------------------------------------
wall = Image.new('RGBA', (W, H), (0, 0, 0, 0))
wd = ImageDraw.Draw(wall)
for yy in range(H):
    t = yy / H
    r = int(214 + (188 - 214) * t)
    g = int(224 + (202 - 224) * t)
    b = int(245 + (232 - 245) * t)
    wd.line([(0, yy), (W, yy)], fill=(r, g, b, 255))
wall.paste(canvas, (0, 0), canvas)
wall.save(os.path.join(OUT_DIR, 'icon_r300_shadow_on_wallpaper.png'))

# ------------------------------------------------------------------
# 8. Mid-grey background -> the outer shadow is easiest to see here
# ------------------------------------------------------------------
mid_bg = Image.new('RGBA', (W, H), (200, 200, 200, 255))
mid_bg.paste(canvas, (0, 0), canvas)
mid_bg.save(os.path.join(OUT_DIR, 'icon_r300_shadow_on_grey.png'))

print(f"Saved {os.path.join(OUT_DIR, 'icon_r300_shadow.png')}")
print(f"Saved {os.path.join(OUT_DIR, 'icon_r300_shadow_on_bg.png')}")
print(f"Saved {os.path.join(OUT_DIR, 'icon_r300_shadow_on_wallpaper.png')}")
print(f"Saved {os.path.join(OUT_DIR, 'icon_r300_shadow_on_grey.png')}")
