import assert from "node:assert/strict";
import { test } from "node:test";
import { createQualityAttempt, observeFirstFrame, parseQualityEvent, qualityBrowser, type QualityEvent } from "./experience-quality.ts";
import { summarize } from "../scripts/report-experience-quality.mts";

const context = { attemptId: "12345678-1234-4234-8234-123456789abc", role: "guest", locale: "ko", retry: false } as const;

test("reports deduplicate deliveries and keep failures, retries and incomplete attempts distinct", () => {
  const base: QualityEvent = { version: 1, ...context, event: "started", stage: "session", elapsedMs: 0 };
  const report = summarize([
    base, base,
    { ...base, event: "transport_connected", elapsedMs: 200 },
    { ...base, event: "first_frame", elapsedMs: 500 },
    { ...base, event: "ended", elapsedMs: 900 },
    { ...base, attemptId: "retry", retry: true },
    { ...base, attemptId: "retry", retry: true, event: "failed", stage: "first_frame", code: "timeout", elapsedMs: 15000 },
    { ...base, attemptId: "open" },
    { ...base, attemptId: "orphan", event: "first_frame", elapsedMs: 800 },
  ]);
  assert.equal(report.attempts, 3);
  assert.equal(report.orphanAttempts, 1);
  assert.equal(report.firstFrameSuccessPercent, 33.33);
  assert.equal(report.retryFirstFrameSuccessPercent, 0);
  assert.equal(report.openAttempts, 1);
  assert.equal(report.transportToFrameMs.p50, 300);
  assert.deepEqual(report.failureStages, { "first_frame:timeout": 1 });
  assert.equal(summarize([]).firstFrameSuccessPercent, null);
});

test("transport success does not report a rendered frame; duplicate and late events are ignored", () => {
  let time = 100;
  const events: QualityEvent[] = [];
  const attempt = createQualityAttempt(context, (event) => events.push(event), () => time);
  attempt.stage("transport");
  time = 600;
  attempt.connected();
  attempt.connected();
  assert.deepEqual(events.map((v) => v.event), ["started", "transport_connected"]);
  attempt.stage("first_frame");
  time = 800;
  attempt.firstFrame();
  attempt.firstFrame();
  attempt.finish(false);
  attempt.fail(new Error("late failure"));
  assert.deepEqual(events.map((v) => [v.event, v.elapsedMs]), [["started", 0], ["transport_connected", 500], ["first_frame", 700], ["ended", 700]]);
});

test("first frame timeout records the failing stage and retry outcome without error contents", () => {
  const events: QualityEvent[] = [];
  const attempt = createQualityAttempt({ ...context, retry: true }, (event) => events.push(event), () => 100);
  attempt.stage("first_frame");
  attempt.fail(new DOMException("SECRET TOKEN", "TimeoutError"));
  attempt.finish(true);
  assert.equal(events.length, 2);
  assert.equal(events[1].stage, "first_frame");
  assert.equal(events[1].code, "timeout");
  assert.equal(events[1].retry, true);
  assert.ok(!JSON.stringify(events).includes("SECRET"));
});

test("permission rejection, cancellation and diagnostics failure preserve lifecycle", () => {
  const events: QualityEvent[] = [];
  const attempt = createQualityAttempt(context, (event) => events.push(event));
  attempt.stage("camera");
  attempt.fail(new DOMException("denied", "NotAllowedError"));
  assert.equal(events[1].code, "permission_denied");
  const cancelled = createQualityAttempt(context, (event) => events.push(event));
  cancelled.finish(true);
  cancelled.firstFrame();
  assert.equal(events.at(-1)?.event, "cancelled");
  assert.doesNotThrow(() => createQualityAttempt(context, () => { throw new Error("collector failed"); }).connected());
});

test("collector schema rejects identifying extras and invalid timings", () => {
  const event = { version: 1, ...context, event: "started", stage: "session", elapsedMs: 0 };
  assert.ok(parseQualityEvent(event));
  for (const extra of [{ token: "secret" }, { sdp: "secret" }, { code: "timeout" }, { elapsedMs: -1 }, { elapsedMs: Infinity }, { elapsedMs: 0.5 }, { elapsedMs: 86_400_001 }, { role: "admin" }]) {
    assert.equal(parseQualityEvent({ ...event, ...extra }), null);
  }
});

test("frame callback waits for composition, fires once and ignores cancelled callbacks", () => {
  let callback: (() => void) | undefined;
  let calls = 0;
  let cancelled = 0;
  const video = {
    requestVideoFrameCallback(cb: () => void) { callback = cb; return 7; },
    cancelVideoFrameCallback(id: number) { cancelled = id; },
    removeEventListener() {},
  } as unknown as HTMLVideoElement;
  const dispose = observeFirstFrame(video, () => calls++);
  assert.equal(calls, 0);
  callback?.(); callback?.();
  assert.equal(calls, 1);
  dispose();
  assert.equal(cancelled, 7);
  const stop = observeFirstFrame(video, () => calls++);
  stop(); callback?.();
  assert.equal(calls, 1);
});

test("fallback requires playback and decoded dimensions, and removes its listeners", () => {
  const listeners = new Map<string, () => void>();
  let frames = 0;
  const video = {
    paused: true, readyState: 2, videoWidth: 640, videoHeight: 480,
    addEventListener(name: string, cb: () => void) { listeners.set(name, cb); },
    removeEventListener(name: string) { listeners.delete(name); },
  };
  observeFirstFrame(video as unknown as HTMLVideoElement, () => frames++);
  listeners.get("loadeddata")?.();
  assert.equal(frames, 0);
  video.paused = false;
  listeners.get("playing")?.();
  assert.equal(frames, 1);
  assert.equal(listeners.size, 0);
});

test("browser classification retains only a coarse family", () => {
  assert.equal(qualityBrowser("Version/26.0 Safari/605.1.15"), "safari");
  assert.equal(qualityBrowser("Chrome/140.0 Safari/537.36"), "chrome");
  assert.equal(qualityBrowser("Chrome/140.0 Safari/537.36 Edg/140.0"), "edge");
  assert.equal(qualityBrowser("FxiOS/140.0 Safari/605.1.15"), "firefox");
  assert.equal(qualityBrowser("CriOS/140.0 Safari/605.1.15"), "chrome");
  assert.equal(qualityBrowser(""), "unknown");
  assert.equal(qualityBrowser("custom-agent"), "other");
});
