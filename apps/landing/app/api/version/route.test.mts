import assert from "node:assert/strict";
import test from "node:test";

test("배포 버전과 캐시 금지 정책을 응답한다", async () => {
  let route: typeof import("./route.ts");

  try {
    route = await import("./route.ts");
  } catch {
    assert.fail("배포 버전 라우트가 필요합니다");
  }

  const previousRevision = process.env.INNOLIVE_WEB_REVISION;
  const revision = "a".repeat(40);
  process.env.INNOLIVE_WEB_REVISION = revision;

  try {
    const response = await route.GET();

    assert.equal(response.status, 200);
    assert.equal(response.headers.get("cache-control"), "no-store");
    assert.deepEqual(await response.json(), { revision });
  } finally {
    if (previousRevision === undefined) {
      delete process.env.INNOLIVE_WEB_REVISION;
    } else {
      process.env.INNOLIVE_WEB_REVISION = previousRevision;
    }
  }
});
