/* eslint-disable @typescript-eslint/no-require-imports */

const assert = require("node:assert/strict");
const fs = require("node:fs");
const Module = require("node:module");
const path = require("node:path");
const ts = require("typescript");

const landing = path.resolve(__dirname, "..");
const resolveFilename = Module._resolveFilename;

Module._resolveFilename = function resolveIntroAlias(request, parent, isMain, options) {
  if (request.startsWith("@/")) {
    request = path.join(landing, request.slice(2));
  }
  return resolveFilename.call(this, request, parent, isMain, options);
};

require.extensions[".tsx"] = function compileTsx(module, filename) {
  const source = fs.readFileSync(filename, "utf8");
  module._compile(
    ts.transpileModule(source, {
      compilerOptions: {
        jsx: ts.JsxEmit.ReactJSX,
        module: ts.ModuleKind.CommonJS,
        moduleResolution: ts.ModuleResolutionKind.NodeNext,
        target: ts.ScriptTarget.ES2017,
      },
      fileName: filename,
    }).outputText,
    filename,
  );
};

const { introScenes } = require("../components/intro-scenes.tsx");
const { nestBox } = require("../components/intro-scene.tsx");

assert.deepEqual(
  introScenes.map(({ id }) => id),
  ["p1", "p2", "p3", "p4", "p5", "p6", "p7"],
);
assert.deepEqual(nestBox({ t: 10, r: 20, b: 30, l: 40 }, { t: 25, r: 50, b: 75, l: 0 }), {
  t: 25,
  r: 40,
  b: 75,
  l: 40,
});

for (const [index, scene] of introScenes.entries()) {
  assert.equal(scene.overlays.length, index);
  assert.deepEqual(scene.overlays.slice(0, -1), index ? introScenes[index - 1].overlays : []);
  for (const { box, mask } of scene.overlays) {
    assert.ok(100 - box.t - box.b < 12, "mask must stay within the face height");
    assert.ok(100 - box.l - box.r < 6, "mask must stay within the face width");
    assert.equal(mask, "/intro/masks/face.svg");
    assert.ok(!(box.l < 74 && 100 - box.r > 70 && box.t < 36 && 100 - box.b > 26), "presenter must remain visible");
  }
}

console.log("intro scenes: OK");

const vm = require("node:vm");
const listeners = new Map();
const layers = introScenes.map(() => ({
  setAttribute() {},
  removeAttribute() {},
}));
const root = {
  style: {}, dataset: {},
  querySelectorAll: () => layers,
  querySelector: () => ({ focus() {} }),
  addEventListener: (name, handler) => listeners.set(name, handler),
  removeEventListener: (name) => listeners.delete(name),
};
const background = [{ inert: false }, { inert: true }];
const document = {
  body: { style: { overflow: "auto" } },
  documentElement: { dataset: {} },
  querySelectorAll: () => background,
};
let effect;
let refCount = 0;
let finished = false;
let currentProgress = 0;
let timelineKilled = false;
let now = 0;
let exitTimer;
const smoother = {
  isPaused: false, top: 3200,
  paused(value) {
    if (value === undefined) return this.isPaused;
    this.isPaused = value;
    return this;
  },
  scrollTop(value) { this.top = value; return this; },
};
const timeline = {
  totalDuration: 0,
  to(target, vars, position) {
    this.totalDuration += (vars.duration ?? 1) + (position ? Number(position.slice(2)) : 0);
    return this;
  },
  duration() { return this.totalDuration; },
  set() { return this; },
  progress(value) { currentProgress = value; return this; },
  kill() { timelineKilled = true; },
};
const mocks = {
  react: {
    useLayoutEffect: (callback) => { effect = callback; },
    useRef: (value) => ({ current: refCount++ === 0 ? root : value }),
    useState: () => [false, (value) => { finished = value; }],
  },
  gsap: {
    timeline: (options) => {
      assert.equal(options.paused, true, "scroll timeline must not play on its own");
      assert.equal(options.defaults.ease, "none", "scroll progress must stay linear");
      return timeline;
    },
    set() {},
    delayedCall: (delay, callback) => {
      exitTimer = {
        deadline: now + delay * 1000, killed: false,
        kill() { this.killed = true; },
        callback,
      };
      return exitTimer;
    },
    to: (target, options) => {
      assert.equal(options.duration, 0, "scroll completion must not add a timed fade");
      options.onComplete();
      return { kill() {} };
    },
  },
  "gsap/ScrollSmoother": { ScrollSmoother: { get: () => smoother } },
  "@/components/intro-scene": { IntroScene() {} },
  "@/components/intro-scenes": { introScenes },
  "@/components/locale-provider": { useLocale: () => ({ messages: { intro: {} } }) },
  "@/lib/locales": { interpolate: () => "" },
};
const introModule = { exports: {} };
vm.runInNewContext(ts.transpileModule(
  fs.readFileSync(path.join(landing, "components/intro-scroll.tsx"), "utf8"),
  { compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.CommonJS, esModuleInterop: true } },
).outputText + "\nmodule.exports.scrollSpeed = INTRO_SCROLL_SPEED; module.exports.focusScrollRange = INTRO_FOCUS_SCROLL_RANGE; module.exports.lockMs = INTRO_EXIT_SCROLL_LOCK_MS;", {
  require: (name) => mocks[name] ?? require(name),
  module: introModule, exports: introModule.exports,
  document, innerHeight: 800, Event,
  window: {
    location: { hash: "" },
    matchMedia: () => ({ matches: false, addEventListener() {}, removeEventListener() {} }),
    scrollTo() {}, dispatchEvent() {},
  },
});
introModule.exports.IntroScroll();
const cleanup = effect();
const speed = introModule.exports.scrollSpeed;
const lockMs = introModule.exports.lockMs;
const focusScrollRange = introModule.exports.focusScrollRange;
const scrollRange = introScenes.length + focusScrollRange;
const tick = (milliseconds) => {
  now += milliseconds;
  if (exitTimer && !exitTimer.killed && now >= exitTimer.deadline) {
    exitTimer.kill();
    exitTimer.callback();
  }
};
const input = {
  prevented: false, stopped: false,
  preventDefault() { this.prevented = true; },
  stopPropagation() { this.stopped = true; },
};
const wheel = (deltaY, deltaMode = 0) => {
  const event = { ...input, deltaY, deltaMode };
  listeners.get("wheel")(event);
  assert.equal(event.prevented, true);
  assert.equal(event.stopped, true, "intro wheel must not reach the page scroll normalizer");
};
const closeTo = (actual, expected) => assert.ok(Math.abs(actual - expected) < 1e-9, `${actual} != ${expected}`);
wheel(8);
closeTo(currentProgress, 0.01 * speed / scrollRange);
wheel(392);
closeTo(currentProgress, 0.5 * speed / scrollRange);
wheel(-200);
closeTo(currentProgress, 0.25 * speed / scrollRange);
wheel(-800);
assert.equal(currentProgress, 0, "reverse input must clamp at the first scene");
wheel(25, 1);
closeTo(currentProgress, 0.5 * speed / scrollRange);
wheel(1, 2);
closeTo(currentProgress, 1.5 * speed / scrollRange);
listeners.get("touchstart")({ touches: [{ clientY: 600 }] });
const touchMove = (clientY) => {
  const event = { ...input, touches: [{ clientY }] };
  listeners.get("touchmove")(event);
  assert.equal(event.prevented, true);
  assert.equal(event.stopped, true, "intro touch must not reach the page scroll normalizer");
};
touchMove(400);
closeTo(currentProgress, 1.75 * speed / scrollRange);
touchMove(500);
closeTo(currentProgress, 1.625 * speed / scrollRange);
listeners.get("keydown")({ key: "ArrowDown", preventDefault() {} });
closeTo(currentProgress, (1.625 * speed + 1) / scrollRange);
wheel(-800 * 20);
wheel(800 * (introScenes.length - 1) / speed);
assert.equal(root.dataset.sceneIndex, "6");
wheel(800 * focusScrollRange / 2 / speed);
closeTo(currentProgress, (6 + focusScrollRange / 2) / scrollRange);
assert.equal(root.dataset.sceneIndex, "6", "the added scroll range must stay on the focus scene");
assert.equal(exitTimer, undefined, "the focus scroll range must not trigger timed completion");
touchMove(-800 * 20);
assert.equal(currentProgress, 1);
assert.equal(timelineKilled, true, "intro progression must stop as soon as it exits");
assert.equal(finished, false, "page must remain locked for the configured exit duration");
assert.equal(document.body.style.overflow, "hidden");
assert.equal(smoother.top, 0, "page scroll must reset to the hero");
assert.equal(smoother.isPaused, false, "new scroll input must remain enabled");
touchMove(0);
assert.equal(currentProgress, 1, "exit must not rewind the completed intro");
wheel(40);
tick(lockMs / 2);
assert.equal(finished, false);
wheel(-400);
assert.equal(currentProgress, 1);
tick(lockMs / 2);
assert.equal(finished, true, "new input must not extend the fixed exit lock");
assert.equal(finished, true);
assert.equal(document.documentElement.dataset.introComplete, "true");
assert.equal(document.body.style.overflow, "auto");
assert.deepEqual(background.map(({ inert }) => inert), [false, true]);
cleanup();
assert.equal(timelineKilled, true);
assert.equal(listeners.size, 0);
console.log("intro scroll: OK");
