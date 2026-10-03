# Wanted Sans loading

`WantedSansVariable.woff2` is the licensed source font. The generated subsets
retain its 12,032 supported Unicode codepoints and its `wght` axis (400–1000).
The source has no Japanese kana or Han glyphs; the existing system fallback
continues rendering these characters.

`next/font/local` preloads `subsets/latin.woff2`. Korean and Japanese layouts
preload the common chunk using `wantedSansCommonPreload` from `app/fonts.ts`.
The common font lives in `public/fonts/wanted-sans/` with a content hash filename;
the generated manifest and CSS use that same URL to avoid duplicate requests.
The CSS imports the other
subsets with disjoint `unicode-range` declarations. A common chunk includes
characters from the locale messages, SEO FAQ copy, and component text. Remaining glyphs stay available in
512-codepoint range chunks, so changing copy or entering additional Korean
text does not lose coverage. Browsers request only chunks used by rendered text.

Regenerate after updating the source font or those copy sources:

```sh
python3 -m venv /tmp/innolive-font-subsets
/tmp/innolive-font-subsets/bin/pip install 'fonttools[woff]==4.59.2'
/tmp/innolive-font-subsets/bin/python scripts/subset-wanted-sans.py
```

Run these commands from `apps/landing`. The script verifies exact cmap coverage,
non-overlapping chunks, unchanged horizontal glyph metrics, and preservation
of the variable weight axis. It writes
`subsets/manifest.json` with file sizes and Unicode ranges. The original OFL
license applies to the subsets.
