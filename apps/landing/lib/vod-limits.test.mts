import assert from "node:assert/strict";
import test from "node:test";
import {
  VOD_ACCEPTED_LABEL,
  VOD_ACCEPT_ATTRIBUTE,
  VOD_MAX_BYTES,
  VOD_MAX_DURATION_MIN,
  VOD_MAX_DURATION_SEC,
  VOD_MAX_GIGABYTES,
  VOD_ORIGINAL_DELETE_HOURS,
  VOD_RESULT_RETENTION_DAYS,
  isAcceptedVodType,
  validateVodDuration,
  validateVodFile,
} from "./vod-limits.ts";

test("한도 상수는 소유자 결정값과 일치", () => {
  assert.equal(VOD_MAX_BYTES, 2 * 1024 ** 3);
  assert.equal(VOD_MAX_GIGABYTES, 2);
  assert.equal(VOD_MAX_DURATION_SEC, 3600);
  assert.equal(VOD_MAX_DURATION_MIN, 60);
  assert.equal(VOD_RESULT_RETENTION_DAYS, 7);
  assert.equal(VOD_ORIGINAL_DELETE_HOURS, 24);
  assert.equal(VOD_ACCEPTED_LABEL, "MP4, MOV, WEBM");
  assert.ok(VOD_ACCEPT_ATTRIBUTE.includes("video/mp4"));
  assert.ok(VOD_ACCEPT_ATTRIBUTE.includes(".mov"));
});

test("허용 형식 판별은 MIME 우선, 확장자 폴백", () => {
  assert.equal(isAcceptedVodType({ name: "a.mp4", type: "video/mp4", size: 1 }), true);
  assert.equal(isAcceptedVodType({ name: "a.mov", type: "video/quicktime", size: 1 }), true);
  assert.equal(isAcceptedVodType({ name: "a.webm", type: "video/webm", size: 1 }), true);
  // Empty MIME falls back to extension (common for .mov).
  assert.equal(isAcceptedVodType({ name: "clip.MOV", type: "", size: 1 }), true);
  assert.equal(isAcceptedVodType({ name: "doc.pdf", type: "application/pdf", size: 1 }), false);
  assert.equal(isAcceptedVodType({ name: "noext", type: "", size: 1 }), false);
});

test("파일 검증은 형식과 크기를 거른다", () => {
  assert.equal(validateVodFile({ name: "a.mp4", type: "video/mp4", size: 1024 }), null);
  assert.equal(validateVodFile({ name: "a.txt", type: "text/plain", size: 1024 }), "type");
  assert.equal(validateVodFile({ name: "a.mp4", type: "video/mp4", size: VOD_MAX_BYTES + 1 }), "size");
  assert.equal(validateVodFile({ name: "a.mp4", type: "video/mp4", size: VOD_MAX_BYTES }), null);
});

test("길이 검증은 메타데이터 실패와 초과를 구분", () => {
  assert.equal(validateVodDuration(120), null);
  assert.equal(validateVodDuration(VOD_MAX_DURATION_SEC), null);
  assert.equal(validateVodDuration(VOD_MAX_DURATION_SEC + 1), "duration");
  assert.equal(validateVodDuration(0), "metadata");
  assert.equal(validateVodDuration(Number.NaN), "metadata");
  assert.equal(validateVodDuration(Infinity), "metadata");
});
