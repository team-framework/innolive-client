import { qualityBrowser } from "./experience-quality.ts";

export const conversionEvents = ["landing_viewed", "pricing_viewed", "tryout_viewed", "experience_started", "experience_succeeded", "signup_viewed", "signup_verification_sent", "signup_completed"] as const;
export type ConversionEventName = typeof conversionEvents[number];
export type ConversionEvent = { version: 1; eventId: string; visitId: string; sequence: number; event: ConversionEventName; properties: { locale: "ko" | "en" | "ja"; browser: string } };
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
export function parseConversionEvent(value: unknown): ConversionEvent | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const v = value as Record<string, unknown>;
  if (Object.keys(v).some(k => !["version", "eventId", "visitId", "sequence", "event", "properties"].includes(k)) || v.version !== 1 || typeof v.eventId !== "string" || !uuid.test(v.eventId) || typeof v.visitId !== "string" || !uuid.test(v.visitId) || !Number.isSafeInteger(v.sequence) || (v.sequence as number) < 1 || (v.sequence as number) > 1_000_000 || !conversionEvents.includes(v.event as ConversionEventName)) return null;
  if (!v.properties || typeof v.properties !== "object" || Array.isArray(v.properties)) return null;
  const props = v.properties as Record<string, unknown>;
  if (Object.keys(props).length !== 2 || !["ko", "en", "ja"].includes(props.locale as string) || !["chrome", "safari", "firefox", "edge", "other", "unknown"].includes(props.browser as string)) return null;
  return { version: 1, eventId: v.eventId, visitId: v.visitId, sequence: v.sequence as number, event: v.event as ConversionEventName, properties: { locale: props.locale as "ko" | "en" | "ja", browser: props.browser as string } };
}

let fallbackVisit: { id: string; sequence: number; expires: number } | undefined;
// Thirty minutes of inactivity starts a new visit; no durable identity or account linkage.
export function nextVisit(storage: Pick<Storage, "getItem" | "setItem">, now = Date.now()) {
  let visit: typeof fallbackVisit;
  try {
    const saved = JSON.parse(storage.getItem("innolive.analytics.visit.v1") ?? "null");
    if (saved && uuid.test(saved.id) && Number.isSafeInteger(saved.sequence) && saved.sequence >= 0 && saved.sequence < 1_000_000 && saved.expires > now && saved.expires <= now + 1_800_000) visit = saved;
  } catch { /* Storage may be blocked. */ }
  visit ??= fallbackVisit && fallbackVisit.expires > now ? fallbackVisit : { id: crypto.randomUUID(), sequence: 0, expires: now + 1_800_000 };
  visit = { id: visit.id, sequence: visit.sequence + 1, expires: now + 1_800_000 };
  fallbackVisit = visit;
  try { storage.setItem("innolive.analytics.visit.v1", JSON.stringify(visit)); } catch { /* Keep an in-memory visit. */ }
  return visit;
}
export function trackConversion(event: ConversionEventName, locale: "ko" | "en" | "ja") {
  try {
    // Respect the browser's explicit tracking preference.
    if (navigator.doNotTrack === "1") return;
    let storage: Pick<Storage, "getItem" | "setItem">;
    try { storage = window.sessionStorage; } catch { storage = { getItem: () => null, setItem: () => undefined }; }
    const visit = nextVisit(storage);
    const body = JSON.stringify({ version: 1, eventId: crypto.randomUUID(), visitId: visit.id, sequence: visit.sequence, event, properties: { locale, browser: qualityBrowser(navigator.userAgent) } });
    if (navigator.sendBeacon?.("/api/analytics", new Blob([body], { type: "application/json" }))) return;
    void fetch("/api/analytics", { method: "POST", headers: { "Content-Type": "application/json" }, body, credentials: "omit", keepalive: true }).catch(() => undefined);
  } catch { /* Analytics never blocks the product flow. */ }
}
