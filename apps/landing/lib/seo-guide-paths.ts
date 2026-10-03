// Korean search guides have no translated equivalent yet.
export const seoGuidePaths = [
  "/live-mosaic", "/face-mosaic", "/live-privacy", "/youtube", "/chzzk",
  "/mosaic-software", "/blog/live-face-blur", "/blog/automatic-mosaic",
] as const;

export function isSeoGuidePath(path: string): boolean {
  return (seoGuidePaths as readonly string[]).includes(path.replace(/\/$/, ""));
}
