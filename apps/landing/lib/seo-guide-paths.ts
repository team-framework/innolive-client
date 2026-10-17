// Only advertise languages with complete guide translations.
export const seoGuideLocales = ["ko", "en"] as const;
export const seoGuidePaths = [
  "/live-mosaic", "/face-mosaic", "/live-privacy", "/youtube", "/chzzk",
  "/mosaic-software", "/blog/live-face-blur", "/blog/automatic-mosaic",
  "/compare/live-face-blur-apps", "/free-face-blur", "/mobile-live-face-blur",
] as const;

export function isSeoGuidePath(path: string): boolean {
  return (seoGuidePaths as readonly string[]).includes(path.replace(/\/$/, ""));
}
