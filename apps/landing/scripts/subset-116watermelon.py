#!/usr/bin/env python3
"""Subset 116watermelon to the intro lettering copy.

The source font is licensed separately and is not stored in this repository.
Run with fonttools[woff]==4.59.2 in an isolated Python environment:

    python scripts/subset-116watermelon.py /path/to/116watermelon.otf
"""
import json
import sys
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont

APP = Path(__file__).resolve().parents[1]
OUTPUT = APP / "app/fonts/116watermelon/116watermelon-lettering.woff2"
MESSAGES = APP / "messages"
KEYS = ("letteringSafe", "letteringFocus")


def lettering_text():
    text = "".join(chr(code) for code in range(0x20, 0x7F))
    for path in sorted(MESSAGES.glob("*.json")):
        intro = json.loads(path.read_text(encoding="utf-8"))["intro"]
        text += "".join(intro[key] for key in KEYS)
    return text


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: subset-116watermelon.py <116watermelon.otf>")
    font = TTFont(sys.argv[1])
    cmap = font.getBestCmap()
    # Japanese has no glyphs in the source; those characters use the fallback font.
    codepoints = sorted({ord(ch) for ch in lettering_text() if ord(ch) in cmap})

    options = subset.Options()
    options.flavor = "woff2"
    options.layout_features = ["*"]
    options.name_IDs = ["*"]
    options.notdef_outline = True
    subsetter = subset.Subsetter(options)
    subsetter.populate(unicodes=codepoints)
    subsetter.subset(font)

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    font.save(OUTPUT)
    print(f"{OUTPUT.relative_to(APP)}: {len(codepoints)} codepoints, {OUTPUT.stat().st_size} bytes")


if __name__ == "__main__":
    main()
