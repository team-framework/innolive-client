// Single source of truth for VOD upload limits and retention policy (owner decisions).
// The notice copy interpolates these numbers so copy and constants never drift.
export const VOD_MAX_BYTES = 2 * 1024 ** 3; // 2 GB per file
export const VOD_MAX_DURATION_SEC = 3600; // 60 minutes per file
export const VOD_RESULT_RETENTION_DAYS = 7; // results kept for 7 days
export const VOD_ORIGINAL_DELETE_HOURS = 24; // originals deleted within 24h after processing

// Derived display numbers (kept here so the page never recomputes them).
export const VOD_MAX_GIGABYTES = VOD_MAX_BYTES / 1024 ** 3; // 2
export const VOD_MAX_DURATION_MIN = VOD_MAX_DURATION_SEC / 60; // 60

export const VOD_ACCEPTED_FORMATS = [
  { ext: ".mp4", mime: "video/mp4" },
  { ext: ".mov", mime: "video/quicktime" },
  { ext: ".webm", mime: "video/webm" },
] as const;

export const VOD_ACCEPTED_MIME_TYPES = VOD_ACCEPTED_FORMATS.map((format) => format.mime);
export const VOD_ACCEPTED_EXTENSIONS = VOD_ACCEPTED_FORMATS.map((format) => format.ext);

// Human-readable format list for copy (e.g. "MP4, MOV, WEBM").
export const VOD_ACCEPTED_LABEL = VOD_ACCEPTED_EXTENSIONS.map((ext) =>
  ext.slice(1).toUpperCase(),
).join(", ");

// `accept` attribute for the <input type="file">: MIME types plus extensions so browsers
// that report an empty MIME for .mov/.webm still pre-filter in the picker.
export const VOD_ACCEPT_ATTRIBUTE = [...VOD_ACCEPTED_MIME_TYPES, ...VOD_ACCEPTED_EXTENSIONS].join(",");

export type VodFileCandidate = { name: string; type: string; size: number };

// `type`/`size` rejected before upload; `duration`/`metadata` after the <video> probe.
export type VodValidationError = "type" | "size" | "duration" | "metadata";

export function isAcceptedVodType(file: VodFileCandidate): boolean {
  const type = file.type.trim().toLowerCase();
  if (type && (VOD_ACCEPTED_MIME_TYPES as readonly string[]).includes(type)) return true;
  // Some browsers report an empty MIME type (notably for .mov); fall back to the extension.
  const name = file.name.trim().toLowerCase();
  return VOD_ACCEPTED_EXTENSIONS.some((ext) => name.endsWith(ext));
}

// Pure pre-upload validation: wrong type or over the size cap. Duration is checked separately
// because it requires loading the file into a <video> element in the browser.
export function validateVodFile(file: VodFileCandidate): VodValidationError | null {
  if (!isAcceptedVodType(file)) return "type";
  if (file.size > VOD_MAX_BYTES) return "size";
  return null;
}

// Pure duration validation against a value read from <video>.duration (seconds).
export function validateVodDuration(durationSec: number): VodValidationError | null {
  if (!Number.isFinite(durationSec) || durationSec <= 0) return "metadata";
  if (durationSec > VOD_MAX_DURATION_SEC) return "duration";
  return null;
}
