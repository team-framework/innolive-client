/* eslint-disable @typescript-eslint/no-require-imports */
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const ts = require("typescript");
const vm = require("node:vm");

const frames = new Map();
let frameId = 0;
let observer;
const moduleMock = { exports: {} };
vm.runInNewContext(ts.transpileModule(
  fs.readFileSync(path.join(__dirname, "../lib/landing-animation.ts"), "utf8"),
  { compilerOptions: { module: ts.ModuleKind.CommonJS } },
).outputText, {
  module: moduleMock, exports: moduleMock.exports,
  window: {
    requestAnimationFrame(callback) { frames.set(++frameId, callback); return frameId; },
    cancelAnimationFrame(id) { frames.delete(id); },
  },
  IntersectionObserver: class {
    constructor(callback) {
      observer = { callback, observe() {}, disconnect() {} };
      return observer;
    }
  },
});
const { afterPaint, observeSectionAnimation } = moduleMock.exports;
const paint = () => {
  const callbacks = Array.from(frames.values());
  frames.clear();
  callbacks.forEach((callback) => callback());
};

async function main() {
  let started = false;
  afterPaint(() => { started = true; });
  paint();
  assert.equal(started, false, "initial HTML gets a paint before loading animation code");
  paint();
  assert.equal(started, true);

  started = false;
  const cancelFrame = afterPaint(() => { started = true; });
  paint();
  cancelFrame();
  paint();
  assert.equal(started, false);

  let loads = 0;
  let disposals = 0;
  let resolveImport;
  const cancelSection = observeSectionAnimation({}, async () => {
    loads++;
    await new Promise((resolve) => { resolveImport = resolve; });
    return () => { disposals++; };
  });
  observer.callback([{ isIntersecting: false }]);
  assert.equal(loads, 0);
  observer.callback([{ isIntersecting: true }]);
  paint();
  assert.equal(loads, 0, "visible sections must also allow the initial paint");
  paint();
  assert.equal(loads, 1);
  cancelSection();
  resolveImport();
  await new Promise(setImmediate);
  assert.equal(disposals, 1, "late initialization must immediately dispose after unmount");

  const cancelBeforePaint = observeSectionAnimation({}, async () => {
    loads++;
    return () => {};
  });
  observer.callback([{ isIntersecting: true }]);
  cancelBeforePaint();
  paint();
  paint();
  assert.equal(loads, 1, "cancelled sections must not import code");

  const cancelFailure = observeSectionAnimation({}, async () => {
    throw new Error("load failed");
  });
  observer.callback([{ isIntersecting: true }]);
  paint();
  paint();
  await new Promise(setImmediate);
  cancelFailure();
  console.log("animation paint boundary, async cleanup and failure: OK");
}
main().catch((error) => { console.error(error); process.exitCode = 1; });
