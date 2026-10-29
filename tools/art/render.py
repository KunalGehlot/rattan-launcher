"""Frames each painting the way a gallery would and writes the screensaver's pictures.

Usage: python3 -I tools/art/render.py [--frame walnut|black|oak|white] [--only id,id] [--sheet]
Reads tools/art/picks.json and art/masters/<id>.jpg (from fetch.py) and writes art/out/<id>.jpg at
1920x1080 (the TV's UI size) plus art/out/catalog.json, which the screensaver reads. Every picture
is finished here: moulding, mat, bevel and shadows, so the TV only shows and cross-fades JPEGs.

Frame and mat are drawn at twice the size and scaled down; the painting is resized once, straight
from the museum file to its place on screen, so the TV shows it pixel for pixel.
"""
import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageCms, ImageFilter
from scipy import ndimage

ROOT = Path(__file__).resolve().parents[2]
PICKS = Path(__file__).resolve().parent / "picks.json"
MASTERS = ROOT / "art" / "masters"
TEXTURES = ROOT / "art" / "textures"
OUT = ROOT / "art" / "out"

W, H, S = 1920, 1080, 2
FRAME = 60                       # moulding width
TOP, BOTTOM, SIDE = 80, 96, 100  # smallest mat margins inside the moulding; the bottom is weighted
BEVEL = 5                        # the mat's 45-degree cut
LIGHT = np.array([-0.30, -0.85, 0.55]) / np.linalg.norm([-0.30, -0.85, 0.55])  # a picture light, top left
HALF = (LIGHT + [0, 0, 1]) / np.linalg.norm(LIGHT + [0, 0, 1])

# Mat boards (sRGB), a shade below full white so the mat reads as paper, not as a lit panel.
# Light boards get a white core, dark ones a lighter core of their own colour.
MATS = {
    "warm": (226, 220, 207),
    "polar": (226, 227, 224),
    "linen": (212, 204, 188),
    "stone": (184, 179, 169),
    "slate": (58, 63, 69),
    "ink": (30, 35, 46),
    "umber": (48, 41, 36),
    "oxblood": (70, 36, 37),
    "forest": (40, 52, 45),
    "charcoal": (36, 35, 34),
}
DARK_MATS = {"slate", "ink", "umber", "oxblood", "forest", "charcoal"}

STYLES = {
    # Dark walnut-like grain with a thin gilded slip at the sight edge.
    "walnut": dict(texture="wood_table_001", gain=1.15, tint=(0.92, 0.88, 0.86), slip=True, gloss=0.10),
    # The same grain ebonised, with the gilded slip.
    "black": dict(texture="wood_table_001", gain=0.30, tint=(0.95, 0.95, 1.0), slip=True, gloss=0.16),
    "oak": dict(texture="oak_veneer_01", gain=1.0, tint=(1.0, 1.0, 1.0), slip=False, gloss=0.06),
    "white": dict(texture=None, color=(0.80, 0.79, 0.76), slip=False, gloss=0.10),
}
GOLD = (196, 152, 82)


def linear(srgb):
    c = np.asarray(srgb, dtype=np.float32) / 255.0
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def srgb8(lin, rng):
    c = np.clip(lin, 0, 1)
    c = np.where(c <= 0.0031308, c * 12.92, 1.055 * c ** (1 / 2.4) - 0.055) * 255.0
    return np.clip(c + rng.uniform(-0.5, 0.5, c.shape), 0, 255).astype(np.uint8)  # dithered


def profile(t, slip):
    """Height in px across the moulding, from the outer edge (t=0) to the sight edge (t=1)."""
    h = np.zeros_like(t)
    a = t < 0.12
    h[a] = 4.0 * np.sqrt(np.clip(1 - (1 - t[a] / 0.12) ** 2, 0, 1))                 # rounded outer edge
    b = (t >= 0.12) & (t < 0.72)
    h[b] = 4.0 + 0.9 * np.sin(np.pi * (t[b] - 0.12) / 0.60)                         # softly cushioned face
    c = (t >= 0.72) & (t < 0.80)
    u = (t[c] - 0.72) / 0.08
    h[c] = 4.0 - 1.6 * u * u * (3 - 2 * u)                                          # cove
    d = (t >= 0.80) & (t < 0.90)
    h[d] = 2.4 + (1.3 if slip else 0.5) * np.sqrt(np.clip(1 - ((t[d] - 0.85) / 0.05) ** 2, 0, 1))  # bead
    e = t >= 0.90
    h[e] = 2.4 * (1 - (t[e] - 0.90) / 0.10)                                         # sight edge down to the mat
    return h


def gallery_light():
    """The picture light's falloff: full at the top centre, a little dimmer towards the lower corners."""
    y, x = np.mgrid[0:H * S, 0:W * S].astype(np.float32)
    x, y = x / (W * S), y / (H * S)
    return (1 - 0.10 * (((x - 0.5) / 0.75) ** 2 + (y / 1.25) ** 2)).astype(np.float32)


def moulding(style, rng):
    """The frame at 2x: linear RGB, coverage, and how much it shades the mat beneath it."""
    spec = STYLES[style]
    y, x = np.mgrid[0:H * S, 0:W * S].astype(np.float32)
    x1, y1 = (x + 0.5) / S, (y + 0.5) / S
    dist = np.stack([x1, W - x1, y1, H - y1])            # left, right, top, bottom
    order = np.sort(dist, axis=0)
    rail = np.argmin(dist, axis=0)
    s = order[0]
    band = s < FRAME
    t = np.clip(s / FRAME, 0, 1)

    ts = np.linspace(0, 1, 4097)
    slope = np.clip(np.gradient(profile(ts, spec["slip"]), ts * FRAME), -3, 3)
    dh = np.interp(t, ts, slope).astype(np.float32)
    inward = np.array([[1, 0], [-1, 0], [0, 1], [0, -1]], dtype=np.float32)[rail]
    n = np.stack([-dh * inward[..., 0], -dh * inward[..., 1], np.ones_like(dh)], axis=-1)
    n /= np.linalg.norm(n, axis=-1, keepdims=True)
    diffuse = np.clip(n @ LIGHT, 0, None)[..., None]
    shine = np.clip(n @ HALF, 0, None)[..., None]
    flat = LIGHT[2]

    if spec["texture"]:
        tex = linear(np.asarray(Image.open(TEXTURES / f"{spec['texture']}.jpg").convert("RGB")))
        size = tex.shape[0]
        along = np.where(rail < 2, y1, x1) * S * 0.55 + np.array([137, 911, 1403, 1777])[rail]
        across = s * S * 0.55 + np.array([300, 1200, 700, 1600])[rail]
        coords = [np.mod(along[band], size), np.mod(across[band], size)]  # grain runs along each rail
        albedo = np.zeros((H * S, W * S, 3), np.float32)
        for ch in range(3):
            albedo[..., ch][band] = ndimage.map_coordinates(tex[..., ch], coords, order=1, mode="wrap")
        if style == "black":
            albedo = np.repeat(albedo.mean(axis=-1, keepdims=True) * 0.35 + 0.012, 3, axis=-1)  # ebonised
        albedo *= spec["gain"] * np.array(spec["tint"], np.float32)
    else:
        albedo = np.ones((H * S, W * S, 3), np.float32) * np.array(spec["color"], np.float32)
        albedo *= 1 + ndimage.gaussian_filter(rng.standard_normal((H * S, W * S)), 1.0)[..., None] * 0.04
    albedo *= np.array([1.0, 0.97, 1.03, 0.985], np.float32)[rail][..., None]  # four separate sticks

    rgb = albedo * (0.30 + 0.90 * diffuse) / (0.30 + 0.90 * flat) + spec["gloss"] * shine ** 30
    if spec["slip"]:
        gilt = (t >= 0.80) & (t < 0.90)
        leaf = 1 + ndimage.gaussian_filter(rng.standard_normal((H * S, W * S)), 0.8) * 0.06
        gold = linear(GOLD) * leaf[..., None] * (0.25 + 1.0 * diffuse) / (0.25 + flat)
        gold += np.array([1.0, 0.82, 0.52], np.float32) * 0.55 * shine ** 18
        rgb = np.where(gilt[..., None], gold, rgb)
    seam = np.clip(1 - (order[1] - order[0]) / 0.6, 0, 1)  # the mitre joints
    rgb *= (1 - 0.45 * seam * band)[..., None]

    mask = band.astype(np.float32)
    cast = ndimage.shift(mask, (7 * S, 3 * S), order=1, cval=1.0)   # the light is above and to the left
    shade = 1 - 0.42 * ndimage.gaussian_filter(cast, 6 * S) - 0.15 * ndimage.gaussian_filter(mask, 2.5 * S)
    return rgb.astype(np.float32), mask, np.clip(shade, 0, 1).astype(np.float32)


def placement(aspect):
    """The painting's rectangle on screen, as large as the mat margins allow, a little above centre."""
    inner_w, inner_h = W - 2 * FRAME, H - 2 * FRAME
    max_w, max_h = inner_w - 2 * SIDE, inner_h - TOP - BOTTOM
    if aspect >= max_w / max_h:
        w, h = max_w, round(max_w / aspect)
    else:
        w, h = round(max_h * aspect), max_h
    x = FRAME + (inner_w - w) // 2
    y = FRAME + round((inner_h - h) * TOP / (TOP + BOTTOM))
    return x, y, w, h


def mat_board(name, rect, light, paper, shade):
    """The mat at 2x with its bevelled opening; the opening itself is left as a dark gap."""
    color = linear(MATS[name])
    board = color * paper[..., None]
    x, y, w, h = (v * S for v in rect)
    b = BEVEL * S
    ox0, oy0, ox1, oy1 = x - b, y - b, x + w + b, y + h + b
    core = color + (1 - color) * (0.55 if name in DARK_MATS else 1.0) * 0.92
    yy, xx = np.mgrid[oy0:oy1, ox0:ox1].astype(np.float32) + 0.5
    dist = np.stack([xx - ox0, ox1 - xx, yy - oy0, oy1 - yy])
    side = np.argmin(dist, axis=0)
    inward = np.array([[1, 0], [-1, 0], [0, 1], [0, -1]], np.float32)[side]
    n = np.stack([inward[..., 0], inward[..., 1], np.ones(side.shape, np.float32)], axis=-1) / np.sqrt(2)
    lit = (0.55 + 0.60 * np.clip(n @ LIGHT, 0, None)) / (0.55 + 0.60 * LIGHT[2])
    board[oy0:oy1, ox0:ox1] = core * lit[..., None]
    board[y:y + h, x:x + w] = color * 0.18
    return board * (light * shade)[..., None]


def master(pick):
    image = Image.open(MASTERS / f"{pick['id']}.jpg")
    image.draft("RGB", (4000, 4000))  # JPEG decodes huge scans at a fraction of their size
    icc = image.info.get("icc_profile")
    image = image.convert("RGB")
    if icc:
        import io
        source = ImageCms.ImageCmsProfile(io.BytesIO(icc))
        image = ImageCms.profileToProfile(image, source, ImageCms.createProfile("sRGB"), outputMode="RGB")
    trims = pick.get("trim") or {}
    if not isinstance(trims, dict):
        trims = dict.fromkeys(("left", "right", "top", "bottom"), trims)
    for side, trim in trims.items():  # crop away scan borders, backdrops and tacking edges
        w, h = image.size
        box = {"left": (round(w * trim), 0, w, h), "right": (0, 0, w - round(w * trim), h),
               "top": (0, round(h * trim), w, h), "bottom": (0, 0, w, h - round(h * trim))}[side]
        image = image.crop(box)
    return image


def auto_mat(art):
    """A curator's default: warm white board, cool white for cool pictures, a dark board for dark ones."""
    lab = np.asarray(ImageCms.applyTransform(art.resize((64, 64)), ImageCms.buildTransformFromOpenProfiles(
        ImageCms.createProfile("sRGB"), ImageCms.createProfile("LAB"), "RGB", "LAB")))
    lightness = lab[..., 0].mean() / 2.55
    warmth = lab[..., 2].view(np.int8).mean()  # PIL keeps a* and b* as signed bytes
    if lightness < 38:
        return "ink" if warmth < 2 else "umber" if warmth > 12 else "charcoal"
    return "polar" if warmth < -2 else "warm"


def painting(art, rect, light, rng):
    """The painting resized once to its rectangle, recessed under the mat's edge."""
    x, y, w, h = rect
    sized = art.resize((w, h), Image.LANCZOS, reducing_gap=3.0)
    sized = sized.filter(ImageFilter.UnsharpMask(radius=0.8, percent=30, threshold=1))
    pixels = linear(np.asarray(sized))
    pad = 24
    outside = np.ones((h + 2 * pad, w + 2 * pad), np.float32)
    outside[pad:-pad, pad:-pad] = 0
    cast = ndimage.gaussian_filter(ndimage.shift(outside, (4, 2), order=1, cval=1.0), 2.5)
    shade = 1 - 0.38 * cast - 0.22 * ndimage.gaussian_filter(outside, 0.9)
    shade = shade[pad:-pad, pad:-pad]
    lit = np.sqrt(light[y * S:(y + h) * S:S, x * S:(x + w) * S:S])
    return srgb8(pixels * (shade * lit)[..., None], rng)


def render(picks, style, sheet):
    rng = np.random.default_rng(7)
    OUT.mkdir(parents=True, exist_ok=True)
    light = gallery_light()
    cloud = Image.fromarray(rng.standard_normal((27, 48)).astype(np.float32), "F").resize((W * S, H * S), Image.BICUBIC)
    paper = (1 + ndimage.gaussian_filter(rng.standard_normal((H * S, W * S)), 0.8) * 0.035
             + np.asarray(cloud) * 0.008).astype(np.float32)  # fine grain and faint mottling
    frame_rgb, frame_mask, frame_shade = moulding(style, rng)
    catalog, thumbs = [], []
    for pick in picks:
        art = master(pick)
        rect = placement(art.size[0] / art.size[1])
        mat = pick.get("mat") or auto_mat(art)
        board = mat_board(mat, rect, light, paper, frame_shade)
        lin = board * (1 - frame_mask[..., None]) + frame_rgb * frame_mask[..., None]
        canvas = Image.fromarray(srgb8(lin, rng)).resize((W, H), Image.LANCZOS)
        canvas.paste(Image.fromarray(painting(art, rect, light, rng)), rect[:2])
        canvas.save(OUT / f"{pick['id']}.jpg", quality=93, subsampling=0, optimize=True)
        ink = "#E8E2D6" if mat in DARK_MATS else "#2E2A25"
        catalog.append({k: pick[k] for k in ("id", "title", "artist", "date", "museum")} |
                       {"file": f"{pick['id']}.jpg", "rect": list(rect), "mat": mat, "ink": ink})
        thumbs.append(canvas.resize((384, 216), Image.LANCZOS))
        print(f"{pick['id']:52s} {mat:9s} {rect}")
    (OUT / "catalog.json").write_text(json.dumps({"frame": style, "works": catalog}, ensure_ascii=False, indent=1))
    if sheet:
        for start in range(0, len(thumbs), 30):
            page = Image.new("RGB", (384 * 5, 216 * 6), (12, 12, 12))
            for i, thumb in enumerate(thumbs[start:start + 30]):
                page.paste(thumb, (384 * (i % 5), 216 * (i // 5)))
            page.save(OUT / f"sheet-{start // 30 + 1}.jpg", quality=85)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--frame", default="black", choices=sorted(STYLES))
    parser.add_argument("--only", help="comma-separated ids")
    parser.add_argument("--sheet", action="store_true", help="also write contact sheets")
    args = parser.parse_args()
    picks = [p for p in json.loads(PICKS.read_text()) if not p.get("skip")]
    if args.only:
        wanted = set(args.only.split(","))
        picks = [p for p in picks if p["id"] in wanted]
    picks = [p for p in picks if (MASTERS / f"{p['id']}.jpg").exists()]
    render(picks, args.frame, args.sheet)


if __name__ == "__main__":
    main()
