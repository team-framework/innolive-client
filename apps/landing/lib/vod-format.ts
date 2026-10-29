// Pure, locale-agnostic formatting helpers for the VOD UI. Label templates (with a {value}
// placeholder) are passed in by the caller so these stay testable without importing messages.
import { interpolate } from "./locales.ts";

export type VodDurationLabels = {
  hours: string;
  minutes: string;
  seconds: string;
  lessThanMinute: string;
};

// Clamp an already-normalized 0..1 progress value to an integer percentage.
export function formatProgressPercent(progress: number): number {
  if (!Number.isFinite(progress)) return 0;
  const percent = Math.round(progress * 100);
  if (percent < 0) return 0;
  if (percent > 100) return 100;
  return percent;
}

// Format a remaining-time estimate (seconds) into a localized label, e.g. "1시간 5분" / "3분" /
// "40초". Values under a minute but above zero use the "less than a minute" copy.
export function formatDurationLabel(totalSec: number, labels: VodDurationLabels): string {
  if (!Number.isFinite(totalSec) || totalSec <= 0) {
    return interpolate(labels.seconds, { value: "0" });
  }

  const sec = Math.round(totalSec);
  const hours = Math.floor(sec / 3600);
  const minutes = Math.floor((sec % 3600) / 60);
  const seconds = sec % 60;

  if (hours > 0) {
    const parts = [interpolate(labels.hours, { value: String(hours) })];
    if (minutes > 0) parts.push(interpolate(labels.minutes, { value: String(minutes) }));
    return parts.join(" ");
  }
  if (minutes > 0) {
    return interpolate(labels.minutes, { value: String(minutes) });
  }
  if (sec < 60) {
    // Keep sub-minute estimates honest but tidy.
    return seconds >= 30 ? labels.lessThanMinute : interpolate(labels.seconds, { value: String(seconds) });
  }
  return interpolate(labels.seconds, { value: String(seconds) });
}

// Format an ISO timestamp as a localized date+time, or null for an unparseable/empty value.
export const VOD_DISPLAY_TIME_ZONE = "Asia/Seoul";

export function formatDateTimeLabel(iso: string | null | undefined, locale: string): string | null {
  if (!iso) return null;
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return null;
  try {
    // Wire times are UTC RFC 3339; the service is operated in Korea, so show KST regardless of
    // the viewer's machine time zone.
    return new Intl.DateTimeFormat(locale, {
      dateStyle: "medium",
      timeStyle: "short",
      timeZone: VOD_DISPLAY_TIME_ZONE,
    }).format(date);
  } catch {
    return date.toISOString();
  }
}
