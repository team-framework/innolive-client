// Build-time feature flags. Values are inlined by Next from NEXT_PUBLIC_* env at build, so a
// flag that is unset when the production image is built stays off in production forever.

// Only the exact strings "1" or "true" (case-insensitive) enable a flag.
export function parseBooleanFlag(value: string | undefined | null): boolean {
  if (!value) return false;
  const normalized = value.trim().toLowerCase();
  return normalized === "1" || normalized === "true";
}

// Master switch for the InnoLive Video feature. When off: the /[locale]/video route 404s, no
// nav entry renders, and the dev mock routes are unreachable.
export function isInnoliveVideoEnabled(): boolean {
  return parseBooleanFlag(process.env.NEXT_PUBLIC_INNOLIVE_VIDEO_ENABLED);
}

// Dev-only VOD mock. Requires a non-production build, the mock flag, AND the video feature flag.
// NODE_ENV is inlined as "production" in production builds, so this is always false there.
export function isVodMockEnabled(): boolean {
  return (
    process.env.NODE_ENV !== "production" &&
    isInnoliveVideoEnabled() &&
    parseBooleanFlag(process.env.NEXT_PUBLIC_VOD_MOCK)
  );
}
