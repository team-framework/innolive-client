import assert from "node:assert/strict";
import test from "node:test";
import {
  VodRequestError,
  isVodJobGone,
  canDeleteVodJob,
  canDownloadVodResult,
  hasVodProgressBar,
  isActiveVodStatus,
  normalizeProgress,
  normalizeVodStatus,
  parseVodFailure,
  parseVodJob,
  parseVodJobList,
  resolveVodBaseUrl,
  vodResultFileName,
  vodStatusTone,
} from "./vod-client.ts";

test("상태 정규화는 허용 값만 통과하고 canceled 별칭을 수용", () => {
  assert.equal(normalizeVodStatus("processing"), "processing");
  assert.equal(normalizeVodStatus("expired"), "expired");
  // Defensive: the contract omits cancelled, but we accept it (and the US spelling) if emitted.
  assert.equal(normalizeVodStatus("cancelled"), "cancelled");
  assert.equal(normalizeVodStatus("canceled"), "cancelled");
  assert.equal(normalizeVodStatus("unknown"), null);
  assert.equal(normalizeVodStatus(42), null);
});

test("진행률은 0..100 정수 백분율을 0..1로 정규화", () => {
  assert.equal(normalizeProgress(50), 0.5);
  assert.equal(normalizeProgress(37), 0.37);
  assert.equal(normalizeProgress(0), 0);
  assert.equal(normalizeProgress(100), 1);
  assert.equal(normalizeProgress(150), 1);
  assert.equal(normalizeProgress(-1), 0);
  assert.equal(normalizeProgress("x"), 0);
});

test("실패 객체 파싱", () => {
  assert.deepEqual(parseVodFailure({ code: "processing_failed", message: "boom" }), {
    code: "processing_failed",
    message: "boom",
  });
  assert.equal(parseVodFailure(null), null);
  assert.equal(parseVodFailure({}), null);
  assert.deepEqual(parseVodFailure({ message: "m" }), { code: "unknown", message: "m" });
});

test("작업 파싱은 snake_case 계약 필드를 읽고 필수 필드를 검증", () => {
  const job = parseVodJob({
    job_id: "job-1",
    status: "processing",
    progress: 40,
    queue_position: 2,
    eta_sec: 30,
    created_at: "2026-10-26T08:00:00Z",
    updated_at: "2026-10-26T08:10:00Z",
    completed_at: null,
    original_delete_at: null,
    result_expires_at: null,
    failure: null,
  });
  assert.ok(job);
  assert.equal(job?.id, "job-1");
  assert.equal(job?.progress, 0.4);
  assert.equal(job?.queuePosition, 2);
  assert.equal(job?.createdAt, "2026-10-26T08:00:00Z");
  assert.equal(job?.originalDeleteAt, null);
  assert.equal(job?.fileName, null); // contract exposes no original filename

  const failed = parseVodJob({ job_id: "x", status: "failed", failure: { code: "storage_failed", message: "disk" } });
  assert.deepEqual(failed?.failure, { code: "storage_failed", message: "disk" });

  // Missing job_id or bad status -> null.
  assert.equal(parseVodJob({ status: "done" }), null);
  assert.equal(parseVodJob({ job_id: "x", status: "nope" }), null);
  // camelCase fallback still parses (resilience against a non-final server).
  assert.equal(parseVodJob({ id: "y", status: "done", fileName: "clip.mp4" })?.fileName, "clip.mp4");
});

test("목록 파싱은 배열과 envelope 모두 지원하고 불량 항목 제외", () => {
  const fromArray = parseVodJobList([
    { job_id: "a", status: "queued" },
    { job_id: "b", status: "weird" },
    { job_id: "c", status: "done" },
  ]);
  assert.deepEqual(
    fromArray.map((j) => j.id),
    ["a", "c"],
  );
  const fromEnvelope = parseVodJobList({ jobs: [{ job_id: "z", status: "failed" }], next_cursor: null });
  assert.equal(fromEnvelope.length, 1);
  assert.deepEqual(parseVodJobList(null), []);
});

test("상태 매핑 순수 함수", () => {
  assert.equal(isActiveVodStatus("processing"), true);
  assert.equal(isActiveVodStatus("queued"), true);
  assert.equal(isActiveVodStatus("done"), false);
  assert.equal(isActiveVodStatus("expired"), false);
  assert.equal(isActiveVodStatus("cancelled"), false);

  assert.equal(canDownloadVodResult("done"), true);
  assert.equal(canDownloadVodResult("processing"), false);

  assert.equal(canDeleteVodJob("processing"), true);
  assert.equal(canDeleteVodJob("done"), true);

  assert.equal(vodStatusTone("paused"), "paused");
  assert.equal(vodStatusTone("done"), "success");
  assert.equal(vodStatusTone("failed"), "danger");
  assert.equal(vodStatusTone("queued"), "progress");
  assert.equal(vodStatusTone("cancelled"), "neutral");

  assert.equal(hasVodProgressBar("queued"), true);
  assert.equal(hasVodProgressBar("failed"), false);
});

test("결과 다운로드 파일명은 로컬 이름 우선, 없으면 서버 규칙", () => {
  assert.equal(vodResultFileName({ id: "abc", fileName: "clip.mp4" }), "clip.mp4");
  assert.equal(vodResultFileName({ id: "abc", fileName: null }), "vod-abc.mp4");
});

test("베이스 URL 해석은 목업과 실서버(/vod)를 구분", () => {
  assert.equal(resolveVodBaseUrl({ useMock: true, serverUrl: undefined }), "/api/dev-vod");
  assert.equal(
    resolveVodBaseUrl({ useMock: false, serverUrl: "https://api.example.com/" }),
    "https://api.example.com/vod",
  );
  assert.throws(
    () => resolveVodBaseUrl({ useMock: false, serverUrl: undefined }),
    (error: unknown) => error instanceof VodRequestError && error.code === "configuration_error",
  );
});

test("404(삭제됐거나 다른 사용자 작업)는 사라진 작업으로 취급", () => {
  assert.equal(isVodJobGone(new VodRequestError("not_found", "x", 404)), true);
  assert.equal(isVodJobGone(new VodRequestError("request_failed", "x", 500)), false);
  assert.equal(isVodJobGone(new Error("x")), false);
});
