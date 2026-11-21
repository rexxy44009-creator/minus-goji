#!/usr/bin/env python3
"""Generates the textures used by the custom effects (assets/godzilla_mod/textures/fx/*.png). Needs numpy pillow."""
import math, os
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = f"{ROOT}/src/main/resources/assets/godzilla_mod/textures/fx"
os.makedirs(OUT, exist_ok=True)
rng = np.random.default_rng(1954)

def tile_noise(h, w, ch, cw):
    """Value noise that tiles in both axes (ch x cw lattice cells)."""
    g = rng.random((ch, cw))
    ys, xs = np.arange(h) * ch / h, np.arange(w) * cw / w
    y0, x0 = np.floor(ys).astype(int), np.floor(xs).astype(int)
    fy, fx = ys - y0, xs - x0
    fy, fx = fy * fy * (3 - 2 * fy), fx * fx * (3 - 2 * fx)
    y1, x1 = (y0 + 1) % ch, (x0 + 1) % cw
    a, b, c, d = g[y0][:, x0], g[y0][:, x1], g[y1][:, x0], g[y1][:, x1]
    top = a + (b - a) * fx[None, :]; bot = c + (d - c) * fx[None, :]
    return top + (bot - top) * fy[:, None]

def fbm(h, w, base_h, base_w, octaves=5, gain=0.55):
    out, amp, tot = np.zeros((h, w)), 1.0, 0.0
    for o in range(octaves):
        out += amp * tile_noise(h, w, base_h * 2 ** o, base_w * 2 ** o); tot += amp; amp *= gain
    return out / tot

def save(name, rgb, alpha):
    arr = np.dstack([np.clip(rgb, 0, 1), np.clip(alpha, 0, 1)])
    Image.fromarray((arr * 255 + 0.5).astype(np.uint8), "RGBA").save(f"{OUT}/{name}.png")

def grid(h, w):
    yy, xx = np.mgrid[0:h, 0:w]
    return (xx + 0.5) / w * 2 - 1, (yy + 0.5) / h * 2 - 1       # -1..1

# glow: soft radial
x, y = grid(128, 128); r = np.hypot(x, y)
a = np.clip(1 - r, 0, 1) ** 2.3
save("glow", np.ones((128, 128, 3)), a)

# flare: hot core + 4 long rays + 2 faint diagonals
x, y = grid(256, 256); r = np.hypot(x, y)
core = np.clip(1 - r * 1.6, 0, 1) ** 2.0
ray = lambda u, v: np.exp(-np.abs(u) * 2.6) * np.exp(-np.abs(v) * 34.0)
rays = ray(x, y) + ray(y, x)
d1, d2 = (x + y) / math.sqrt(2), (x - y) / math.sqrt(2)
rays += 0.35 * (ray(d1, d2) + ray(d2, d1))
a = np.clip(core + 0.9 * rays * np.clip(1 - r, 0, 1), 0, 1)
save("flare", np.ones((256, 256, 3)), a)

# ring: u tiles around, v across (bright narrow line + soft shoulder)
h, w = 64, 256
v = (np.arange(h) + 0.5) / h
prof = np.exp(-((v - 0.5) / 0.1) ** 2) + 0.45 * np.exp(-((v - 0.5) / 0.32) ** 2)
n = fbm(h, w, 2, 8, 4)
a = np.clip(prof[:, None] * (0.7 + 0.5 * n), 0, 1)
save("ring", np.ones((h, w, 3)), a)

# fire: turbulent tiling noise; alpha carries the structure, colour is near-white (vertex colour tints it)
h = w = 256
n1 = fbm(h, w, 3, 3, 6); n2 = fbm(h, w, 5, 5, 5)
warp = np.roll(n1, 17, axis=0) * 0.5 + n2 * 0.5
a = np.clip((warp - 0.28) * 2.2, 0, 1) ** 1.1
rgb = np.dstack([np.clip(0.75 + 0.25 * n2, 0, 1)] * 3)
save("fire", rgb, a)

# smoke: soft billowing clouds
n = fbm(h, w, 3, 3, 6, 0.6)
a = np.clip((n - 0.30) * 2.4, 0, 1)
a = a * a * (3 - 2 * a)
x, y = grid(h, w); edge = 1.0                      # tiles, so no edge fade
rgb = np.dstack([np.clip(0.82 + 0.18 * fbm(h, w, 6, 6, 3), 0, 1)] * 3)
save("smoke", rgb, a)

# plasma: streaks flowing along u (tileable), bright centre line across v
h, w = 128, 256
st = fbm(h, w, 14, 3, 5, 0.6)                      # many rows, few columns => long streaks along u
v = (np.arange(h) + 0.5) / h
prof = np.exp(-((v - 0.5) / 0.16) ** 2) * 0.9 + 0.25 * np.exp(-((v - 0.5) / 0.45) ** 2)
a = np.clip(prof[:, None] * (0.45 + 0.9 * st), 0, 1)
rgb = np.dstack([np.clip(0.7 + 0.3 * st, 0, 1)] * 3)
save("plasma", rgb, a)

# bolt: thin hot line with glow, tiles along u
h, w = 64, 256
v = (np.arange(h) + 0.5) / h
prof = np.exp(-((v - 0.5) / 0.045) ** 2) + 0.5 * np.exp(-((v - 0.5) / 0.22) ** 2)
a = np.clip(prof[:, None] * (0.85 + 0.15 * fbm(h, w, 1, 12, 3)), 0, 1)
save("bolt", np.ones((h, w, 3)), a)

# spark: small bright dot + faint cross
x, y = grid(64, 64); r = np.hypot(x, y)
a = np.clip(np.exp(-r * r * 12) + 0.3 * (np.exp(-np.abs(x) * 22) + np.exp(-np.abs(y) * 22)) * np.clip(1 - r, 0, 1), 0, 1)
save("spark", np.ones((64, 64, 3)), a)

# chip: irregular rock chunk
x, y = grid(64, 64)
ang = np.arctan2(y, x)
rad = 0.62 + 0.18 * np.sin(ang * 3 + 1.0) + 0.1 * np.sin(ang * 5 + 2.0) + 0.06 * np.sin(ang * 9)
a = np.clip((rad - np.hypot(x, y)) * 9, 0, 1)
g = 0.65 + 0.35 * fbm(64, 64, 4, 4, 3)
save("chip", np.dstack([g, g, g]), a)

# flame: a crystalline energy flame. v=0 is the tip, v=1 the base (matches Fx.upright). Several jagged spikes fused at the root.
SS = 3
H, W = 256 * SS, 128 * SS
im = Image.new("L", (W, H), 0)
dr = ImageDraw.Draw(im)
def spike(cx, base_w, height_frac, lean):
    tip = (cx + lean * W, H * (1 - height_frac))
    # jagged edges: add notches along each side
    pts = [(cx - base_w / 2, H)]
    for k in range(1, 6):
        f = k / 6.0
        px = cx - base_w / 2 * (1 - f) + lean * W * f
        pts.append((px - (8 if k % 2 else -3) * SS * (1 - f), H - (H - tip[1]) * f))
    pts.append(tip)
    for k in range(5, 0, -1):
        f = k / 6.0
        px = cx + base_w / 2 * (1 - f) + lean * W * f
        pts.append((px + (8 if k % 2 else -3) * SS * (1 - f), H - (H - tip[1]) * f))
    pts.append((cx + base_w / 2, H))
    dr.polygon(pts, fill=255)
spike(W * 0.50, W * 0.30, 0.98, 0.02)
spike(W * 0.30, W * 0.22, 0.66, -0.10)
spike(W * 0.70, W * 0.22, 0.72, 0.10)
spike(W * 0.16, W * 0.14, 0.38, -0.12)
spike(W * 0.85, W * 0.14, 0.42, 0.12)
spike(W * 0.40, W * 0.16, 0.80, -0.04)
spike(W * 0.60, W * 0.16, 0.86, 0.05)
im = im.filter(ImageFilter.GaussianBlur(2.0 * SS)).resize((128, 256), Image.LANCZOS)
shape = np.asarray(im, dtype=np.float32) / 255.0
yy = np.linspace(0, 1, 256)[:, None]                                    # 0 tip .. 1 base
n = fbm(256, 128, 8, 4, 4)
inner = np.clip(1.0 - yy * 0.9, 0, 1)                                    # hotter toward the tip? keep base hot, tip cooler
a = np.clip(shape * (0.55 + 0.6 * n) * (0.5 + 0.9 * yy ** 0.6), 0, 1)
rgb = np.dstack([np.clip(0.7 + 0.3 * n, 0, 1)] * 3)
save("flame", rgb, a)
print("fx textures written to", OUT, sorted(os.listdir(OUT)))
