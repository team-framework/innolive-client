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

const p6 = introScenes[5];
const p7 = introScenes[6];
const oldMask = "/intro/masks/s6-g7.svg";
const newMask = "/intro/masks/s7-g7.svg";
const fifthPerson = p6.overlays.find(({ mask }) => mask === oldMask);
const preservedFifthPerson = p7.overlays.find(({ mask }) => mask === oldMask);
const newPerson = p7.overlays.find(({ mask }) => mask === newMask);

assert.ok(fifthPerson, "p6 must blur the fifth person");
assert.deepEqual(preservedFifthPerson, fifthPerson, "p7 must preserve the fifth-person blur");
assert.deepEqual(newPerson?.clipPath, "inset(55% 75% 0 0)");
assert.equal(p7.overlays.some(({ mask }) => mask === "/intro/masks/s7-v4.svg"), false);

console.log("intro scenes: OK");
