/* eslint-disable @typescript-eslint/no-require-imports */

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const ts = require("typescript");
const vm = require("node:vm");

const source = ts.transpileModule(
  fs.readFileSync(path.join(__dirname, "../components/privacy-section.tsx"), "utf8"),
  { compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.CommonJS, esModuleInterop: true } },
).outputText + "\nmodule.exports.delayMs = PRIVACY_ANIMATION_DELAY_MS;";

const slides = [{}, {}, {}];
const section = { querySelectorAll: () => slides };
const smoother = {
  isPaused: false, top: 1200,
  paused(value) {
    if (value === undefined) return this.isPaused;
    this.isPaused = value;
    return this;
  },
  scrollTop(value) { this.top = value; return this; },
};
let effect;
let entry;
let timer;
let mobileAnimation;
let reverted = false;
let timerCount = 0;
const setups = new Map();
const mocks = {
  react: {
    useRef: () => ({ current: section }),
    useLayoutEffect: (callback) => { effect = callback; },
  },
  "next/image": () => null,
  "@/components/locale-provider": { useLocale: () => ({ messages: { privacy: { slides: [] } } }) },
  "@/lib/privacy-slides": { privacySlideSources: ["one.png", "two.png", "three.png"] },
  "gsap/ScrollTrigger": { ScrollTrigger: {} },
  "gsap/ScrollSmoother": { ScrollSmoother: { get: () => smoother } },
  gsap: {
    registerPlugin() {},
    set() {},
    matchMedia: () => ({ add: (query, setup) => setups.set(query, setup), revert() {} }),
    context: (setup) => { setup(); return { revert() { reverted = true; } }; },
    timeline: ({ scrollTrigger }) => {
      entry = scrollTrigger.onEnter;
      return { to() { return this; } };
    },
    delayedCall: (delay, callback) => {
      timerCount++;
      timer = { delay, callback, killed: false, kill() { this.killed = true; } };
      return timer;
    },
    from: (target, options) => { mobileAnimation = options; },
  },
};
const privacyModule = { exports: {} };
vm.runInNewContext(source, {
  require: (name) => mocks[name] ?? require(name),
  module: privacyModule, exports: privacyModule.exports,
});
privacyModule.exports.PrivacySection();
effect();
const delaySeconds = privacyModule.exports.delayMs / 1000;
const setupDesktop = setups.get("(min-width: 64rem) and (prefers-reduced-motion: no-preference)");
const trigger = {
  start: 800, enabled: true,
  animation: { value: 0.3, progress(value) { this.value = value; } },
  disable(revert) { assert.equal(revert, false); this.enabled = false; },
  enable() { this.enabled = true; },
};
const cleanup = setupDesktop();
entry(trigger);
assert.equal(trigger.enabled, false);
assert.equal(trigger.animation.value, 0, "arrival must hold the first slide");
assert.equal(smoother.top, trigger.start, "arrival must discard scroll overshoot");
assert.equal(smoother.isPaused, true);
assert.equal(timer.delay, delaySeconds, "delay must use the configurable milliseconds");
entry(trigger);
assert.equal(timerCount, 1, "extra input must not restart the fixed delay");
timer.callback();
assert.equal(trigger.enabled, true);
assert.equal(smoother.isPaused, false);
entry(trigger);
assert.equal(timerCount, 1, "return scrolling must not repeat the initial delay");
cleanup();
assert.equal(timer.killed, true);
assert.equal(reverted, true);

smoother.isPaused = true;
const cleanupDuringWait = setupDesktop();
entry(trigger);
cleanupDuringWait();
assert.equal(timer.killed, true, "unmount must cancel the pending delay");
assert.equal(trigger.enabled, true);
assert.equal(smoother.isPaused, true, "cleanup must restore the previous pause state");

setups.get("(max-width: 63.999rem) and (prefers-reduced-motion: no-preference)")();
assert.equal(mobileAnimation.delay, delaySeconds);
console.log("privacy arrival delay: OK");
