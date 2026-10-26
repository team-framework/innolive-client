import assert from "node:assert/strict";
import test from "node:test";
import {
  VodRequestError,
  canDeleteVodJob,
  canDownloadVodResult,
  hasVodProgressBar,
  isActiveVodStatus,
  normalizeProgress,
  normalizeVodStatus,
  parseVodJob,
  parseVodJobList,
  resolveVodBaseUrl,
  vodStatusTone,
} from "./vod-client.ts";

test("상태 정규화는 허용 값만 통과", () => {
  assert.equal(normalizeVodStatus("processing"), "processing");
  assert.equal(normalizeVodStatus("expired"), "expired");
  assert.equal(normalizeVodStatus("unknown"), null);
  assert.equal(normalizeVodStatus(42), null);
});

test("진행률은 0..1로 정규화하고 퍼센트도 허용", () => {
  assert.equal(normalizeProgress(0.5), 0.5);
  assert.equal(normalizeProgress(55), 0.55); // 0..100 percentage
  assert.equal(normalizeProgress(1), 1);
  assert.equal(normalizeProgress(150), 1);
  assert.equal(normalizeProgress(-1), 0);
  assert.equal(normalizeProgress("x"), 0);
});

test("작업 파싱은 필수 필드 검증과 기본값 보정", () => {
  const job = parseVodJob({
    id: "job-1",
    fileName: "clip.mp4",
    status: "processing",
    progress: 40,
    queuePosition: 2,
    etaSec: 30,
  });
  assert.ok(job);
  assert.equal(job?.fileName, "clip.mp4");
  assert.equal(job?.progress, 0.4);
  assert.equal(job?.queuePosition, 2);
  assert.equal(job?.originalDeleteAt, null);

  // Missing id or bad status -> null.
  assert.equal(parseVodJob({ status: "done" }), null);
  assert.equal(parseVodJob({ id: "x", status: "nope" }), null);
  // fileName falls back to id when absent.
  assert.equal(parseVodJob({ id: "x", status: "done" })?.fileName, "x");
});

test("목록 파싱은 배열과 envelope 모두 지원하고 불량 항목 제외", () => {
  const fromArray = parseVodJobList([
    { id: "a", status: "queued" },
    { id: "b", status: "weird" },
    { id: "c", status: "done" },
  ]);
  assert.deepEqual(
    fromArray.map((j) => j.id),
    ["a", "c"],
  );
  const fromEnvelope = parseVodJobList({ jobs: [{ id: "z", status: "failed" }] });
  assert.equal(fromEnvelope.length, 1);
  assert.deepEqual(parseVodJobList(null), []);
});

test("상태 매핑 순수 함수", () => {
  assert.equal(isActiveVodStatus("processing"), true);
  assert.equal(isActiveVodStatus("queued"), true);
  assert.equal(isActiveVodStatus("done"), false);
  assert.equal(isActiveVodStatus("expired"), false);

  assert.equal(canDownloadVodResult("done"), true);
  assert.equal(canDownloadVodResult("processing"), false);

  assert.equal(canDeleteVodJob("processing"), true);
  assert.equal(canDeleteVodJob("done"), true);

  assert.equal(vodStatusTone("paused"), "paused");
  assert.equal(vodStatusTone("done"), "success");
  assert.equal(vodStatusTone("failed"), "danger");
  assert.equal(vodStatusTone("queued"), "progress");

  assert.equal(hasVodProgressBar("queued"), true);
  assert.equal(hasVodProgressBar("failed"), false);
});

test("베이스 URL 해석은 목업과 실서버를 구분", () => {
  assert.equal(resolveVodBaseUrl({ useMock: true, serverUrl: undefined }), "/api/dev-vod");
  assert.equal(
    resolveVodBaseUrl({ useMock: false, serverUrl: "https://api.example.com/" }),
    "https://api.example.com/api/v1/vod",
  );
  assert.throws(
    () => resolveVodBaseUrl({ useMock: false, serverUrl: undefined }),
    (error: unknown) => error instanceof VodRequestError && error.code === "configuration_error",
  );
});
