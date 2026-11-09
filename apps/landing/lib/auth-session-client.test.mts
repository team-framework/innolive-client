import assert from "node:assert/strict";
import { test } from "node:test";
import { discardPendingAuthSession, loadAuthSession } from "./auth-session-client.ts";

function deferredSessionResponses(t: import("node:test").TestContext) {
  const pending: Array<(response: Response) => void> = [];
  const fetchMock = t.mock.method(globalThis, "fetch", () =>
    new Promise<Response>((resolve) => pending.push(resolve)),
  );
  const respond = (authenticated: boolean) => {
    for (const resolve of pending.splice(0)) {
      resolve(Response.json({ authenticated }));
    }
  };
  return { fetchMock, respond };
}

test("동시에 마운트된 여러 로그인 상태 확인은 세션 요청을 한 번만 보냄", async (t) => {
  const { fetchMock, respond } = deferredSessionResponses(t);

  const checks = [loadAuthSession(), loadAuthSession(), loadAuthSession(), loadAuthSession()];
  respond(true);

  assert.deepEqual(await Promise.all(checks), [true, true, true, true]);
  assert.equal(fetchMock.mock.callCount(), 1);
});

test("앞선 확인이 끝난 뒤의 확인은 새 세션 요청을 보냄", async (t) => {
  const { fetchMock, respond } = deferredSessionResponses(t);

  const first = loadAuthSession();
  respond(true);
  assert.equal(await first, true);

  const second = loadAuthSession();
  respond(false);
  assert.equal(await second, false);
  assert.equal(fetchMock.mock.callCount(), 2);
});

test("세션 요청이 실패하면 로그아웃 상태로 보고 다음 확인에서 다시 요청함", async (t) => {
  const fetchMock = t.mock.method(globalThis, "fetch", async () => {
    throw new TypeError("network down");
  });

  assert.deepEqual(await Promise.all([loadAuthSession(), loadAuthSession()]), [false, false]);
  assert.equal(await loadAuthSession(), false);
  assert.equal(fetchMock.mock.callCount(), 2);
});

test("로그인 상태가 바뀐 뒤의 확인은 이전 요청 결과를 재사용하지 않음", async (t) => {
  const pending: Array<(response: Response) => void> = [];
  const fetchMock = t.mock.method(globalThis, "fetch", () =>
    new Promise<Response>((resolve) => pending.push(resolve)),
  );

  const beforeSignIn = loadAuthSession();
  discardPendingAuthSession();
  const afterSignIn = loadAuthSession();
  assert.equal(fetchMock.mock.callCount(), 2);

  pending[0](Response.json({ authenticated: false }));
  assert.equal(await beforeSignIn, false);

  const joined = loadAuthSession();
  assert.equal(fetchMock.mock.callCount(), 2);

  pending[1](Response.json({ authenticated: true }));
  assert.deepEqual(await Promise.all([afterSignIn, joined]), [true, true]);
});
