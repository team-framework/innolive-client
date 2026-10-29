import assert from "node:assert/strict";
import test from "node:test";
import { VOD_MOCK_TOTAL_SEC, computeMockState } from "./vod-mock-timeline.ts";

test("목업 수명주기는 정해진 순서를 따른다", () => {
  assert.equal(computeMockState(0).status, "uploaded");
  assert.equal(computeMockState(5).status, "queued");
  assert.equal(computeMockState(20).status, "processing");
  assert.equal(computeMockState(42).status, "paused");
  assert.equal(computeMockState(50).status, "processing");
  assert.equal(computeMockState(VOD_MOCK_TOTAL_SEC + 1).status, "done");
});

test("대기 순번은 감소하고 진행률은 단조 증가", () => {
  const early = computeMockState(4);
  const late = computeMockState(13);
  assert.ok(early.queuePosition !== null && late.queuePosition !== null);
  assert.ok((early.queuePosition ?? 0) >= (late.queuePosition ?? 0));
  assert.ok((late.queuePosition ?? 0) >= 1);

  const samples = [16, 25, 39, 42, 50, 65].map((t) => computeMockState(t).progress);
  for (let i = 1; i < samples.length; i += 1) {
    assert.ok(samples[i] >= samples[i - 1], `progress should not decrease at index ${i}`);
  }
  // Paused keeps progress flat relative to the end of the first processing phase.
  assert.ok(computeMockState(42).progress >= computeMockState(39).progress - 0.01);
});

test("완료 상태의 진행률과 eta", () => {
  const done = computeMockState(VOD_MOCK_TOTAL_SEC + 10);
  assert.equal(done.progress, 1);
  assert.equal(done.queuePosition, null);
  assert.equal(done.etaSec, null);
});

test("speed는 수명주기를 가속한다", () => {
  // At speed 10, the job is done by ~7s of wall clock.
  assert.equal(computeMockState(8, 10).status, "done");
  // Active states always expose a non-negative eta.
  const processing = computeMockState(20);
  assert.ok(processing.etaSec !== null && processing.etaSec >= 0);
});
