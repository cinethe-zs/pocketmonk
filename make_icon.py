"""
Generate PocketMonk launcher icons — a stylish meditating monk.
Outputs ic_launcher.png at all Android mipmap densities.
"""
import os, math
from PIL import Image, ImageDraw

BASE = r"c:\Users\33678\git\pocketmonk\android\app\src\main\res"

SIZES = {
    "mipmap-mdpi":    48,
    "mipmap-hdpi":    72,
    "mipmap-xhdpi":   96,
    "mipmap-xxhdpi":  144,
    "mipmap-xxxhdpi": 192,
}

# Palette (dark theme, monk in saffron)
BG_DARK    = (18, 18, 30)      # deep navy
BG_GLOW    = (42, 36, 80)      # subtle purple glow centre
ROBE       = (200, 130, 30)    # saffron/amber
ROBE_DARK  = (140, 85, 10)     # shadow side of robe
SKIN       = (220, 185, 140)   # head/face
ACCENT     = (255, 180, 50)    # halo ring
BOWL       = (100, 70, 30)     # alms bowl


def draw_monk(size: int) -> Image.Image:
    img  = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    s = size  # shorthand

    # ── Background circle ────────────────────────────────────────────────────
    draw.ellipse([0, 0, s - 1, s - 1], fill=BG_DARK)

    # Inner radial glow
    glow_r = int(s * 0.38)
    cx, cy = s // 2, s // 2
    draw.ellipse(
        [cx - glow_r, cy - glow_r, cx + glow_r, cy + glow_r],
        fill=BG_GLOW,
    )

    # ── Halo ring ────────────────────────────────────────────────────────────
    hr  = int(s * 0.28)
    hw  = max(2, int(s * 0.022))
    hcy = int(s * 0.36)
    draw.ellipse(
        [cx - hr, hcy - hr, cx + hr, hcy + hr],
        outline=ACCENT,
        width=hw,
    )

    # ── Head (bald) ──────────────────────────────────────────────────────────
    head_r  = int(s * 0.135)
    head_cy = int(s * 0.35)
    draw.ellipse(
        [cx - head_r, head_cy - head_r, cx + head_r, head_cy + head_r],
        fill=SKIN,
    )

    # ── Robe body (trapezoid — seated lotus) ─────────────────────────────────
    # Top of robe just below neck, wide at bottom (lotus spread)
    neck_y  = head_cy + head_r - int(s * 0.02)
    robe_top_hw = int(s * 0.12)
    robe_bot_hw = int(s * 0.30)
    robe_bot_y  = int(s * 0.82)
    robe_mid_y  = int(s * 0.60)

    robe_poly = [
        (cx - robe_top_hw, neck_y),
        (cx + robe_top_hw, neck_y),
        (cx + robe_bot_hw, robe_bot_y),
        (cx - robe_bot_hw, robe_bot_y),
    ]
    draw.polygon(robe_poly, fill=ROBE)

    # Shadow fold on the left side
    shadow_poly = [
        (cx - robe_top_hw, neck_y),
        (cx - int(s * 0.03), robe_mid_y),
        (cx - robe_bot_hw, robe_bot_y),
        (cx - robe_bot_hw + int(s * 0.05), robe_bot_y),
    ]
    draw.polygon(shadow_poly, fill=ROBE_DARK)

    # ── Folded hands / alms bowl area ────────────────────────────────────────
    hands_y  = int(s * 0.60)
    bowl_rx  = int(s * 0.10)
    bowl_ry  = int(s * 0.05)
    draw.ellipse(
        [cx - bowl_rx, hands_y - bowl_ry,
         cx + bowl_rx, hands_y + bowl_ry],
        fill=BOWL,
    )
    # Rim highlight
    draw.arc(
        [cx - bowl_rx, hands_y - bowl_ry,
         cx + bowl_rx, hands_y + bowl_ry],
        start=200, end=340,
        fill=ROBE,
        width=max(1, int(s * 0.015)),
    )

    # ── Visible ears ─────────────────────────────────────────────────────────
    ear_r = int(s * 0.04)
    for sign in (-1, 1):
        ex = cx + sign * (head_r - int(s * 0.01))
        ey = head_cy + int(s * 0.02)
        draw.ellipse([ex - ear_r, ey - ear_r, ex + ear_r, ey + ear_r],
                     fill=SKIN)

    # ── Closed-eye suggestion (two small dark arcs) ───────────────────────────
    eye_w  = int(s * 0.04)
    eye_h  = int(s * 0.015)
    eye_y  = head_cy - int(s * 0.01)
    eye_dx = int(s * 0.05)
    for sign in (-1, 1):
        ex = cx + sign * eye_dx
        draw.arc(
            [ex - eye_w, eye_y - eye_h, ex + eye_w, eye_y + eye_h],
            start=200, end=340,
            fill=(80, 50, 20),
            width=max(1, int(s * 0.018)),
        )

    # Clip to circle
    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, s - 1, s - 1], fill=255)
    img.putalpha(mask)

    return img


for folder, size in SIZES.items():
    out_dir = os.path.join(BASE, folder)
    os.makedirs(out_dir, exist_ok=True)
    icon = draw_monk(size)
    # Save with white background (Android doesn't support APNG for launcher)
    bg = Image.new("RGBA", (size, size), (0, 0, 0, 255))
    bg.paste(icon, mask=icon)
    bg.convert("RGB").save(os.path.join(out_dir, "ic_launcher.png"))
    print(f"  {folder}: {size}x{size} ok")

print("Done.")
