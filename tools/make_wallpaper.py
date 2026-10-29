"""Draws the launcher's artwork: a black-brown wood frame around a dark-oak rattan weave.

Usage: python3 -I tools/make_wallpaper.py
Writes into app/src/main/res:
  drawable-nodpi/wallpaper.jpg        1920x1080, the home-screen background
  drawable-nodpi/wallpaper_blur.jpg   480x270, blurred copy that the glass panels show
  drawable-xhdpi/banner.png           320x180 launcher banner
  mipmap-xhdpi/ic_launcher.png        96x96 launcher icon

Everything is drawn from seeded noise, so the output is the same every run. It renders at twice
the TV's UI size (1920x1080) and scales down, which smooths the strip edges.
"""
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

RES = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res"
W, H = 1920, 1080
SS = 2                    # supersampling factor
BORDER = 56               # frame width at 1080p
PITCH, STRIP = 26, 22     # rattan cell size and strip width at 1080p
BLUR = (480, 270, 5)      # size and Gaussian radius of the glass backdrop

WOOD = np.array([33, 29, 26], dtype=float)       # black-brown frame
OAK = np.array([104, 68, 42], dtype=float)       # dark oak cane, fully lit
HOLE = np.array([16, 12, 9], dtype=float)        # gaps between strips

rng = np.random.default_rng(1155)


def noise(h, w, cell_y, cell_x):
    """Smooth noise in [-1, 1], stretched: cell_y and cell_x are the feature sizes in pixels."""
    gh, gw = int(np.ceil(h / cell_y)) + 2, int(np.ceil(w / cell_x)) + 2
    grid = Image.fromarray(rng.standard_normal((gh, gw)).astype(np.float32), mode="F")
    big = np.asarray(grid.resize((int(gw * cell_x), int(gh * cell_y)), Image.BICUBIC))[:h, :w]
    return big / (np.abs(big).max() + 1e-6)


def wood(h, w, vertical):
    """Fine, irregular grain like a black-brown wood-effect foil, with faint speckles."""
    if vertical:
        grain = 0.6 * noise(h, w, 260, 3) + 0.4 * noise(h, w, 90, 1.5)
    else:
        grain = 0.6 * noise(h, w, 3, 260) + 0.4 * noise(h, w, 1.5, 90)
    speckle = noise(h, w, 2, 2)
    shade = 1 + 0.14 * grain + 0.06 * speckle + 0.04 * noise(h, w, 400, 400)
    return WOOD[None, None, :] * shade[..., None]


def rattan(h, w):
    """Plain weave of flat cane strips: each crossing shows one strip on top, alternating."""
    p, s = PITCH * SS, STRIP * SS
    y, x = np.mgrid[0:h, 0:w].astype(float) + 0.5
    col, row = (x // p).astype(int), (y // p).astype(int)
    u, v = x - col * p - p / 2, y - row * p - p / 2          # offsets from the cell centre
    in_h, in_v = np.abs(v) < s / 2, np.abs(u) < s / 2
    h_on_top = (row + col) % 2 == 0

    # Height of each strip as it rises over and dips under the crossing strips.
    height_h = np.cos(np.pi * (x / p - 0.5 + row))
    height_v = -np.cos(np.pi * (y / p - 0.5 + col))
    # Rounded strip edges, fibres running along each strip, a slightly different tone per strip.
    edge_h = 1 - 0.35 * (np.abs(v) / (s / 2)) ** 4
    edge_v = 1 - 0.35 * (np.abs(u) / (s / 2)) ** 4
    fibre_h = 1 + 0.10 * noise(h, w, 1.2, 140) + 0.05 * noise(h, w, 3, 30)
    fibre_v = 1 + 0.10 * noise(h, w, 140, 1.2) + 0.05 * noise(h, w, 30, 3)
    tone_h = (1 + 0.035 * rng.standard_normal(row.max() + 1))[row]
    tone_v = (1 + 0.035 * rng.standard_normal(col.max() + 1))[col]
    # Light from the upper left: a strip rising towards the light is brighter.
    slope_h = -np.sin(np.pi * (x / p - 0.5 + row))
    slope_v = np.sin(np.pi * (y / p - 0.5 + col))

    lit_h = (0.50 + 0.25 * (height_h + 1) + 0.06 * slope_h) * edge_h * fibre_h * tone_h
    lit_v = (0.50 + 0.25 * (height_v + 1) + 0.06 * slope_v) * edge_v * fibre_v * tone_v

    show_h = in_h & (~in_v | h_on_top)
    show_v = in_v & ~show_h
    out = np.empty((h, w, 3))
    out[:] = HOLE * (1 + 0.15 * noise(h, w, 6, 6))[..., None]
    out[show_h] = OAK * lit_h[show_h][:, None]
    out[show_v] = OAK * lit_v[show_v][:, None]
    return out


def inner_shadow(h, w):
    """The weave sits behind the frame: shadow under the top edge, less on the left."""
    y, x = np.mgrid[0:h, 0:w].astype(float)
    top = np.exp(-y / (16 * SS))
    left = np.exp(-x / (10 * SS))
    right = np.exp(-(w - 1 - x) / (6 * SS))
    bottom = np.exp(-(h - 1 - y) / (5 * SS))
    return 1 - np.clip(0.75 * top + 0.45 * left + 0.25 * right + 0.25 * bottom, 0, 0.85)


def wallpaper():
    w, h, b = W * SS, H * SS, BORDER * SS
    img = wood(h, w, vertical=False)
    stiles = wood(h, w, vertical=True)
    img[:, :b] = stiles[:, :b]
    img[:, w - b:] = stiles[:, w - b:]
    # Thin dark seams where the rails butt against the stiles.
    img[:, b - SS:b + SS] *= 0.55
    img[:, w - b - SS:w - b + SS] *= 0.55

    top, bottom, left, right = b, h - b, b, w - b
    ph, pw = bottom - top, right - left
    img[top:bottom, left:right] = rattan(ph, pw) * inner_shadow(ph, pw)[..., None]

    # Edges of the opening: a light chamfer on the rail below, a dark line under the rail above.
    img[bottom:bottom + 2 * SS, left:right] = img[bottom:bottom + 2 * SS, left:right] * 1.9 + 6
    img[top - SS:top, left:right] *= 0.5
    img[top:bottom, right:right + SS] *= 1.5

    out = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGB")
    return out.resize((W, H), Image.LANCZOS)


def main():
    full = wallpaper()
    full.save(RES / "drawable-nodpi" / "wallpaper.jpg", quality=90, optimize=True)

    bw, bh, radius = BLUR
    blur = full.resize((bw, bh), Image.LANCZOS).filter(ImageFilter.GaussianBlur(radius))
    blur.save(RES / "drawable-nodpi" / "wallpaper_blur.jpg", quality=92, optimize=True)

    # Banner and icon: the frame's top-left corner with a stretch of weave.
    full.crop((0, 0, 640, 360)).resize((320, 180), Image.LANCZOS).save(RES / "drawable-xhdpi" / "banner.png")
    full.crop((0, 0, 288, 288)).resize((96, 96), Image.LANCZOS).save(RES / "mipmap-xhdpi" / "ic_launcher.png")
    print("wrote", RES)


if __name__ == "__main__":
    main()
