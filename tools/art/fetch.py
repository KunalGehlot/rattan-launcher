"""Downloads the screensaver's paintings and the frame textures.

Usage: python3 -I tools/art/fetch.py
Reads tools/art/picks.json and saves each work's image to art/masters/<id>.jpg (about 3000-4000 px
wide), skipping files already there. Every pick is public domain: museum open-access images (CC0)
or the museums' own scans on Wikimedia Commons. The frame's wood textures come from Poly Haven (CC0).
"""
import json
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
PICKS = Path(__file__).resolve().parent / "picks.json"
MASTERS = ROOT / "art" / "masters"
TEXTURES = ROOT / "art" / "textures"
# AIC's image server sits behind a bot check that lets browser user agents through.
BROWSER = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Safari/537.36"
POLITE = "RattanArtFetch/1.0 (https://github.com/KunalGehlot/rattan-launcher)"
WOODS = ["wood_table_001", "oak_veneer_01"]


def get(url, agent=POLITE, tries=4):
    for attempt in range(tries):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": agent, "Referer": "https://www.artic.edu/"})
            with urllib.request.urlopen(request, timeout=120) as response:
                return response.read()
        except Exception as error:  # noqa: BLE001 - any network error is retried the same way
            if attempt == tries - 1:
                raise
            print(f"  retry ({error})", file=sys.stderr)
            time.sleep(5 * (attempt + 1))


def commons_url(title):
    """The 3840 px rendition of a Commons file (one of the sizes Commons keeps ready)."""
    query = urllib.parse.urlencode({"action": "query", "titles": title, "prop": "imageinfo", "iiprop": "url|size",
                                    "iiurlwidth": 3840, "format": "json"})
    page = next(iter(json.loads(get("https://commons.wikimedia.org/w/api.php?" + query))["query"]["pages"].values()))
    info = page["imageinfo"][0]
    return info["thumburl"] if info["width"] > 3840 else info["url"]


def fetch_picks():
    MASTERS.mkdir(parents=True, exist_ok=True)
    for pick in json.loads(PICKS.read_text()):
        path = MASTERS / f"{pick['id']}.jpg"
        if path.exists():
            continue
        url = pick["image"]
        if url.startswith("File:"):
            url = commons_url(url)
            time.sleep(2)
        print("fetch", pick["id"])
        data = get(url, BROWSER if "artic.edu" in url else POLITE)
        part = path.with_suffix(".part")
        part.write_bytes(data)
        with Image.open(part) as image:
            print(f"  {image.size[0]}x{image.size[1]}")
        part.rename(path)
        time.sleep(2 if "wikimedia" in url else 0.5)


def fetch_textures():
    TEXTURES.mkdir(parents=True, exist_ok=True)
    for name in WOODS:
        path = TEXTURES / f"{name}.jpg"
        if not path.exists():
            files = json.loads(get(f"https://api.polyhaven.com/files/{name}"))
            path.write_bytes(get(files["Diffuse"]["2k"]["jpg"]["url"]))
            print("texture", name)


if __name__ == "__main__":
    fetch_textures()
    fetch_picks()
