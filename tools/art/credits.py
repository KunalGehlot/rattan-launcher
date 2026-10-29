"""Writes docs/paintings.md, the credits for every painting the screensaver shows.

Usage: python3 -I tools/art/credits.py
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
picks = [p for p in json.loads((ROOT / "tools" / "art" / "picks.json").read_text()) if not p.get("skip")]
lines = [
    "# Paintings in the screensaver",
    "",
    f"{len(picks)} works, all in the public domain. The images come from the museums' open-access",
    "collections (CC0) or are the museums' own photographs on Wikimedia Commons; each painting's wall",
    "label on the TV names its museum. Sorted by artist.",
    "",
    "| Artist | Title | Date | Collection | Image |",
    "| --- | --- | --- | --- | --- |",
]
SORT_AS = {"Pieter Bruegel the Elder": "Bruegel", "Rembrandt van Rijn": "Rembrandt"}
for p in sorted(picks, key=lambda p: (SORT_AS.get(p["artist"], p["artist"].split()[-1]), p["title"])):
    cells = [p["artist"], f"*{p['title']}*", p["date"], p["museum"], f"[{p['licence']}]({p['page']})"]
    lines.append("| " + " | ".join(c.replace("|", "\\|") for c in cells) + " |")
(ROOT / "docs" / "paintings.md").write_text("\n".join(lines) + "\n")
print(f"wrote docs/paintings.md ({len(picks)} works)")
