import assert from "node:assert/strict";
import test from "node:test";
import { formatDurationLabel, formatProgressPercent } from "./vod-format.ts";

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
