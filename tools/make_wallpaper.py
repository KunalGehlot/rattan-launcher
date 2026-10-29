"""Prepares the launcher's background, its frosted copy, banner and icon from one photo.

Usage: python3 -I tools/make_wallpaper.py <photo.jpg>
Writes into app/src/main/res:
  drawable-nodpi/wallpaper.jpg        1920x1080, the home-screen background (the TV's UI size)
  drawable-nodpi/wallpaper_frost.jpg  960x540, softened copy the glass panels mix in
  drawable-xhdpi/banner.png           320x180 launcher banner
  mipmap-xhdpi/ic_launcher.png        96x96 launcher icon

The background is the whole photo, not a crop: scaled to the screen's width its lines stay as fine
as the photographer shot them, and a photo of vertical lines does not mind fitting a 3:2 frame to
16:9 by height (lines stay lines).
"""
import io
import sys
from pathlib import Path

from PIL import Image, ImageCms, ImageFilter

RES = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res"
W, H = 1920, 1080


def srgb(photo):
    """The photo in sRGB, whatever profile the camera or the site embedded."""
    icc = photo.info.get("icc_profile")
    photo = photo.convert("RGB")
    if icc:
        source = ImageCms.ImageCmsProfile(io.BytesIO(icc))
        photo = ImageCms.profileToProfile(photo, source, ImageCms.createProfile("sRGB"), outputMode="RGB")
    return photo


def cover(photo, w, h):
    """Crops the photo's centre to the target's aspect ratio, then scales it down."""
    sw, sh = photo.size
    scale = max(w / sw, h / sh)
    cw, ch = round(w / scale), round(h / scale)
    left, top = (sw - cw) // 2, (sh - ch) // 2
    return photo.crop((left, top, left + cw, top + ch)).resize((w, h), Image.LANCZOS)


def main():
    photo = srgb(Image.open(sys.argv[1]))
    # A light unsharp mask keeps the lines crisp after the TV scales 1080p up to its 4K panel.
    full = photo.resize((W, H), Image.LANCZOS, reducing_gap=3.0)
    full = full.filter(ImageFilter.UnsharpMask(radius=1.0, percent=30, threshold=2))
    full.save(RES / "drawable-nodpi" / "wallpaper.jpg", quality=95, subsampling=0, optimize=True)

    frost = full.resize((W // 2, H // 2), Image.LANCZOS).filter(ImageFilter.GaussianBlur(10))
    frost.save(RES / "drawable-nodpi" / "wallpaper_frost.jpg", quality=90, optimize=True)

    photo.resize((320, 180), Image.LANCZOS, reducing_gap=3.0).save(RES / "drawable-xhdpi" / "banner.png")
    cover(photo, 96, 96).save(RES / "mipmap-xhdpi" / "ic_launcher.png")
    print("wrote", RES)


if __name__ == "__main__":
    main()
