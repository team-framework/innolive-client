import assert from "node:assert/strict";
import { test, type TestContext } from "node:test";
import { POST } from "./route.ts";

const event = { version: 1, attemptId: "12345678-1234-4234-8234-123456789abc", event: "failed", role: "guest", locale: "ko", retry: true, stage: "camera", elapsedMs: 500, code: "permission_denied", browser: "safari" };
function request(body: string, origin = "https://example.test", type = "application/json") {
  return new Request("https://example.test/api/experience-quality", { method: "POST", headers: { origin, "content-type": type, cookie: "user_session=private", authorization: "Bearer user-token" }, body });
}
function setEnv(t: TestContext, name: string, value: string) {
  const previous = process.env[name];
  process.env[name] = value;
  t.after(() => { if (previous === undefined) delete process.env[name]; else process.env[name] = previous; });
}
function configure(t: TestContext) {
  setEnv(t, "EXPERIENCE_QUALITY_SERVER_URL", "https://collector.test");
  setEnv(t, "EXPERIENCE_QUALITY_INGEST_KEY", "test-collector-key-at-least-32-chars");
  setEnv(t, "INNOLIVE_WEB_REVISION", "abc123");
}

test("forwards only validated measurements and service authentication; acknowledges after persistence", async (t) => {
  configure(t);
  let forwarded: Request | undefined;
  t.mock.method(globalThis, "fetch", async (url: string, init: RequestInit) => {
    forwarded = new Request(url, init);
    return new Response(null, { status: 204 });
  });
  const response = await POST(request(JSON.stringify(event)));
  assert.equal(response.status, 204);
  assert.equal(response.headers.get("cache-control"), "no-store");
  assert.ok(forwarded);
  assert.equal(forwarded.url, "https://collector.test/experience-quality");
  assert.equal(forwarded.headers.get("cookie"), null);
  assert.equal(forwarded.headers.get("authorization"), "Bearer test-collector-key-at-least-32-chars");
  assert.equal(forwarded.credentials, "omit");
  assert.equal(forwarded.redirect, "error");
  assert.deepEqual(await forwarded.json(), { ...event, release: "abc123" });
});

test("invalid, identifying and oversized payloads never reach the server", async (t) => {
  configure(t);
  const fetch = t.mock.method(globalThis, "fetch", async () => new Response(null, { status: 204 }));
  assert.equal((await POST(request(JSON.stringify(event), "https://attacker.test"))).status, 403);
  assert.equal((await POST(request(JSON.stringify(event), "https://example.test", "text/plain"))).status, 415);
  assert.equal((await POST(request("{"))).status, 400);
  assert.equal((await POST(request(JSON.stringify({ ...event, access_token: "secret" })))).status, 400);
  assert.equal((await POST(request(JSON.stringify({ ...event, browser: "raw-user-agent" })))).status, 400);
  assert.equal((await POST(request(JSON.stringify({ ...event, release: "spoofed" })))).status, 400);
  assert.equal((await POST(request("x".repeat(1025)))).status, 413);
  assert.equal(fetch.mock.callCount(), 0);
});

test("configuration, persistence and transport failures return 503 without logging payloads or secrets", async (t) => {
  configure(t);
  const logs: unknown[] = [];
  t.mock.method(console, "warn", (value: unknown) => logs.push(value));
  const fetch = t.mock.method(globalThis, "fetch", async () => new Response(null, { status: 500 }));
  assert.equal((await POST(request(JSON.stringify(event)))).status, 503);
  fetch.mock.mockImplementation(async () => { throw new Error("private backend URL or key"); });
  assert.equal((await POST(request(JSON.stringify(event)))).status, 503);
  setEnv(t, "EXPERIENCE_QUALITY_INGEST_KEY", "");
  assert.equal((await POST(request(JSON.stringify(event)))).status, 503);
  assert.equal(fetch.mock.callCount(), 2);
  assert.deepEqual(logs, Array(3).fill(JSON.stringify({ kind: "experience_quality_storage_unavailable" })));
});

test("collection is bounded per process without persisting IP or user identifiers", async (t) => {
  configure(t);
  t.mock.method(Date, "now", () => 180000);
  const fetch = t.mock.method(globalThis, "fetch", async () => new Response(null, { status: 204 }));
  for (let i = 0; i < 600; i++) assert.equal((await POST(request(JSON.stringify(event)))).status, 204);
  const response = await POST(request(JSON.stringify(event)));
  assert.equal(response.status, 429);
  assert.equal(response.headers.get("retry-after"), "60");
  assert.equal(fetch.mock.callCount(), 600);
});

test("same-origin checks use the incoming host and proxy protocol, never forwarded host", async (t) => {
  configure(t);
  t.mock.method(globalThis, "fetch", async () => new Response(null, { status: 204 }));
  const make = (origin: string, extra: Record<string,string> = {}) => new Request("http://container:3000/api/experience-quality", {
    method: "POST", body: JSON.stringify(event), headers: { host: "site.test", "x-forwarded-proto": "https", origin, "content-type": "application/json", ...extra },
  });
  assert.equal((await POST(make("https://site.test"))).status, 204);
  assert.equal((await POST(make("https://attacker.test", { "x-forwarded-host": "attacker.test" }))).status, 403);
  assert.equal((await POST(make("https://site.test", { "sec-fetch-site": "cross-site" }))).status, 403);
});
