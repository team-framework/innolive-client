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

// True for every `next build` output (Next inlines NODE_ENV as "production" there) and for the
// production runtime. Read as a literal `process.env.NODE_ENV` so Next can inline it.
export function isProductionBuild(): boolean {
  return process.env.NODE_ENV === "production";
}

// Dev-only VOD mock. Requires a non-production build, the mock flag, AND the video feature flag.
// The production check comes first and is unconditional: a production build never enables the
// mock, even if NEXT_PUBLIC_VOD_MOCK=1 was baked in at build time.
export function isVodMockEnabled(): boolean {
  if (process.env.NODE_ENV === "production") return false;
  return isInnoliveVideoEnabled() && parseBooleanFlag(process.env.NEXT_PUBLIC_VOD_MOCK);
}
