import localFont from "next/font/local";
import wantedSansManifest from "./fonts/wanted-sans/subsets/manifest.json";

export const wantedSansCommonPreload = wantedSansManifest.common_preload;

export const wantedSans = localFont({
  src: "./fonts/wanted-sans/subsets/latin.woff2",
  display: "swap",
  weight: "400 1000",
  variable: "--font-wanted-sans",
  // Remaining glyphs use the unicode-range faces in fonts/wanted-sans.css.
  // Keep them ahead of system fonts, which may also contain Korean glyphs.
  fallback: ["Wanted Sans", "system-ui", "sans-serif"],
  adjustFontFallback: false,
  declarations: [{ prop: "unicode-range", value: "U+0-FF" }],
});
