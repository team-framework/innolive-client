import assert from "node:assert/strict";
import { test } from "node:test";
import { POST } from "./route.ts";

const event = { version: 1, attemptId: "12345678-1234-4234-8234-123456789abc", event: "failed", role: "guest", locale: "ko", retry: true, stage: "camera", elapsedMs: 500, code: "permission_denied" };
function request(body: string, origin = "https://example.test", type = "application/json") {
  return new Request("https://example.test/api/experience-quality", { method: "POST", headers: { origin, "content-type": type }, body });
}

test("valid diagnostics log only the allowlisted event and return no-store", async (t) => {
  const logs: string[] = [];
  t.mock.method(console, "info", (value: string) => logs.push(value));
  const response = await POST(request(JSON.stringify(event)));
  assert.equal(response.status, 204);
  assert.equal(response.headers.get("cache-control"), "no-store");
  const log = JSON.parse(logs[0]);
  assert.equal(log.kind, "experience_quality");
  assert.equal(log.attemptId, event.attemptId);
  assert.equal(log.code, "permission_denied");
});

test("cross-origin, malformed, oversized and identifying payloads never reach logs", async (t) => {
  const info = t.mock.method(console, "info", () => {});
  assert.equal((await POST(request(JSON.stringify(event), "https://attacker.test"))).status, 403);
  assert.equal((await POST(request(JSON.stringify(event), "https://example.test", "text/plain"))).status, 415);
  assert.equal((await POST(request("{"))).status, 400);
  assert.equal((await POST(request(JSON.stringify({ ...event, access_token: "secret" })))).status, 400);
  assert.equal((await POST(request("x".repeat(1025)))).status, 413);
  assert.equal(info.mock.callCount(), 0);
});
