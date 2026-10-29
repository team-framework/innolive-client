// Pure time-based state machine for the dev mock. Both the mock route handlers and the unit
// tests derive job state from elapsed seconds, so the lifecycle has no hidden timers.
//
// Lifecycle (at speed = 1): uploaded -> queued (position 3->1) -> processing (progress rising)
// -> paused (live has priority) -> processing -> done. Whole lifecycle ~70s.

export type VodMockStatus = "uploaded" | "queued" | "processing" | "paused" | "done";

type Phase = { status: Exclude<VodMockStatus, "done">; durationSec: number };

const PHASES: Phase[] = [
  { status: "uploaded", durationSec: 2 },
  { status: "queued", durationSec: 12 },
  { status: "processing", durationSec: 26 },
  { status: "paused", durationSec: 8 },
  { status: "processing", durationSec: 22 },
];

export const VOD_MOCK_TOTAL_SEC = PHASES.reduce((sum, phase) => sum + phase.durationSec, 0);

const TOTAL_PROCESSING_SEC = PHASES.filter((phase) => phase.status === "processing").reduce(
  (sum, phase) => sum + phase.durationSec,
  0,
);

const QUEUE_START_POSITION = 3;

export type VodMockState = {
  status: VodMockStatus;
  progress: number; // 0..1
  queuePosition: number | null;
  etaSec: number | null;
};

function clamp01(value: number): number {
  if (value < 0) return 0;
  if (value > 1) return 1;
  return value;
}

// `speed` > 1 makes the lifecycle run faster. `elapsedSec` is wall-clock seconds since upload.
export function computeMockState(elapsedSec: number, speed = 1): VodMockState {
  const safeSpeed = Number.isFinite(speed) && speed > 0 ? speed : 1;
  const virtual = Math.max(0, elapsedSec) * safeSpeed; // virtual seconds along the timeline

  let acc = 0;
  let processedProcessing = 0;
  for (const phase of PHASES) {
    const end = acc + phase.durationSec;
    if (virtual < end) {
      const within = virtual - acc;
      const processing = processedProcessing + (phase.status === "processing" ? within : 0);
      const progress = clamp01(processing / TOTAL_PROCESSING_SEC);
      const queuePosition =
        phase.status === "queued"
          ? Math.max(1, Math.ceil(QUEUE_START_POSITION * (1 - within / phase.durationSec)))
          : null;
      const etaSec = Math.max(0, Math.round((VOD_MOCK_TOTAL_SEC - virtual) / safeSpeed));
      return { status: phase.status, progress, queuePosition, etaSec };
    }
    acc = end;
    if (phase.status === "processing") processedProcessing += phase.durationSec;
  }

  return { status: "done", progress: 1, queuePosition: null, etaSec: null };
}
