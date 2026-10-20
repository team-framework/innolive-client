// FIX 2026 booth notice. Shown until broadcasts resume on 2026-10-25 00:00 KST.
export const notice = {
  id: "fix2026",
  endsAt: Date.parse("2026-10-25T00:00:00+09:00"),
  contactEmail: "contact@innolive.studio",
} as const;

export const NOTICE_HIDE_DURATION_MS = 24 * 60 * 60 * 1000;

// "Don't show for a day" survives reloads; plain close only lasts for the tab session.
export const noticeHiddenUntilKey = `innolive:notice-hidden-until:${notice.id}`;
export const noticeClosedKey = `innolive:notice-closed:${notice.id}`;

export function isNoticeActive(now: number) {
  return now < notice.endsAt;
}

export function isNoticeHidden(now: number, hiddenUntil: string | null) {
  const until = Number(hiddenUntil);
  return Number.isFinite(until) && until > now;
}
