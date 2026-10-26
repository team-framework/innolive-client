// The finalized contract exposes no original filename (download names are server-generated).
// For a friendlier list, the browser remembers the name it uploaded, keyed by job id, in
// sessionStorage so it survives reloads within the session. Unknown jobs fall back to the
// server-style name in vodResultFileName().
const STORAGE_KEY = "innolive.vod.names";

function read(): Record<string, string> {
  if (typeof window === "undefined") return {};
  try {
    const raw = window.sessionStorage.getItem(STORAGE_KEY);
    const parsed = raw ? (JSON.parse(raw) as unknown) : null;
    return parsed && typeof parsed === "object" ? (parsed as Record<string, string>) : {};
  } catch {
    return {};
  }
}

export function readVodNames(): Record<string, string> {
  return read();
}

export function rememberVodName(id: string, fileName: string): Record<string, string> {
  const next = { ...read(), [id]: fileName };
  try {
    window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(next));
  } catch {
    // Storage may be unavailable (private mode); the in-memory copy still works for this view.
  }
  return next;
}

export function forgetVodName(id: string): Record<string, string> {
  const next = read();
  delete next[id];
  try {
    window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(next));
  } catch {
    // ignore
  }
  return next;
}
