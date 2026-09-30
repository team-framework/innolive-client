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
