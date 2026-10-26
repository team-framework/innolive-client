import assert from "node:assert/strict";
import test from "node:test";
import { formatDateTimeLabel, formatDurationLabel, formatProgressPercent } from "./vod-format.ts";

const labels = {
  hours: "{value}시간",
  minutes: "{value}분",
  seconds: "{value}초",
  lessThanMinute: "1분 미만",
};

test("진행률 퍼센트는 0~100으로 반올림/클램프", () => {
  assert.equal(formatProgressPercent(0), 0);
  assert.equal(formatProgressPercent(0.5), 50);
  assert.equal(formatProgressPercent(0.555), 56);
  assert.equal(formatProgressPercent(1), 100);
  assert.equal(formatProgressPercent(1.5), 100);
  assert.equal(formatProgressPercent(-1), 0);
  assert.equal(formatProgressPercent(Number.NaN), 0);
});

test("남은 시간 라벨은 로케일 템플릿으로 구성", () => {
  assert.equal(formatDurationLabel(3900, labels), "1시간 5분");
  assert.equal(formatDurationLabel(3600, labels), "1시간");
  assert.equal(formatDurationLabel(180, labels), "3분");
  assert.equal(formatDurationLabel(45, labels), "1분 미만");
  assert.equal(formatDurationLabel(10, labels), "10초");
  assert.equal(formatDurationLabel(0, labels), "0초");
  assert.equal(formatDurationLabel(-5, labels), "0초");
});

test("UTC RFC 3339 시각은 뷰어 시간대와 무관하게 KST로 표시", () => {
  const label = formatDateTimeLabel("2026-10-26T15:30:00Z", "en-US");
  assert.ok(label?.includes("Oct 27, 2026"), label ?? "null");
  assert.ok(label?.includes("12:30"), label ?? "null");
  assert.equal(formatDateTimeLabel(null, "ko"), null);
  assert.equal(formatDateTimeLabel("not-a-date", "ko"), null);
});
