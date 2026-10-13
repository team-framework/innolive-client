import { createReadStream } from "node:fs";
import { createInterface } from "node:readline";
import { fileURLToPath } from "node:url";
import { parseQualityEvent, type QualityEvent } from "../lib/experience-quality.ts";

function percentile(values: number[], percent: number) {
  if (!values.length) return null;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.max(0, Math.ceil(sorted.length * percent) - 1)];
}

export function summarize(events: QualityEvent[]) {
  const attempts = new Map<string, Map<QualityEvent["event"], QualityEvent>>();
  for (const event of events) {
    const attempt = attempts.get(event.attemptId) ?? new Map();
    if (!attempt.has(event.event)) attempt.set(event.event, event);
    attempts.set(event.attemptId, attempt);
  }
  // Exclude orphan events when a log window starts midway through an attempt.
  const cohort = [...attempts.values()].filter((attempt) => attempt.has("started"));
  const count = (event: QualityEvent["event"]) => cohort.filter((attempt) => attempt.has(event)).length;
  const retries = cohort.filter((attempt) => attempt.get("started")?.retry);
  const failureStages: Record<string, number> = {};
  for (const attempt of cohort) {
    const failure = attempt.get("failed");
    if (failure) {
      const key = `${failure.stage}:${failure.code}`;
      failureStages[key] = (failureStages[key] ?? 0) + 1;
    }
  }
  const connectionTimes = cohort.flatMap((attempt) => attempt.get("transport_connected") ? [attempt.get("transport_connected")!.elapsedMs] : []);
  const frameTimes = cohort.flatMap((attempt) => attempt.get("first_frame") ? [attempt.get("first_frame")!.elapsedMs] : []);
  const delays = cohort.flatMap((attempt) => {
    const connected = attempt.get("transport_connected");
    const frame = attempt.get("first_frame");
    return connected && frame ? [Math.max(0, frame.elapsedMs - connected.elapsedMs)] : [];
  });
  const rate = (success: number, total: number) => total ? Math.round(success / total * 10000) / 100 : null;
  return {
    attempts: cohort.length,
    orphanAttempts: attempts.size - cohort.length,
    transportConnected: count("transport_connected"),
    firstFrame: count("first_frame"),
    firstFrameSuccessPercent: rate(count("first_frame"), cohort.length),
    failed: count("failed"), cancelled: count("cancelled"), ended: count("ended"),
    openAttempts: cohort.filter((attempt) => !["failed", "cancelled", "ended"].some((event) => attempt.has(event as QualityEvent["event"]))).length,
    retryAttempts: retries.length,
    retryFirstFrameSuccessPercent: rate(retries.filter((attempt) => attempt.has("first_frame")).length, retries.length),
    connectionMs: { p50: percentile(connectionTimes, .5), p95: percentile(connectionTimes, .95) },
    firstFrameMs: { p50: percentile(frameTimes, .5), p95: percentile(frameTimes, .95) },
    transportToFrameMs: { p50: percentile(delays, .5), p95: percentile(delays, .95) },
    failureStages,
  };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const input = process.argv[2] ? createReadStream(process.argv[2]) : process.stdin;
  const events: QualityEvent[] = [];
  for await (const line of createInterface({ input })) {
    try {
      const value = JSON.parse(line.slice(line.indexOf("{")));
      if (value.kind !== "experience_quality") continue;
      const { kind, receivedAt, ...payload } = value;
      void kind; void receivedAt;
      const event = parseQualityEvent(payload);
      if (event) events.push(event);
    } catch { /* Skip unrelated container output. */ }
  }
  console.log(JSON.stringify(summarize(events), null, 2));
}
