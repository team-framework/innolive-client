/* eslint-disable @typescript-eslint/no-require-imports */
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const ts = require("typescript");

// Load the actual modules with the same aliases as Next.js, without a server.
const appRoot = path.resolve(__dirname, "..");
const modules = new Map();
function load(file) {
  if (file.endsWith(".json")) return JSON.parse(fs.readFileSync(file, "utf8"));
  if (modules.has(file)) return modules.get(file).exports;
  const loadedModule = { exports: {} };
  modules.set(file, loadedModule);
  const source = ts.transpileModule(fs.readFileSync(file, "utf8"), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, esModuleInterop: true },
  }).outputText;
  const localRequire = (id) => {
    if (!id.startsWith("@/")) return require(id);
    const target = path.join(appRoot, id.slice(2));
    return load(path.extname(target) ? target : `${target}.ts`);
  };
  vm.runInThisContext(`(function(require, module, exports) {\n${source}\n})`, { filename: file })(localRequire, loadedModule, loadedModule.exports);
  return loadedModule.exports;
}

const { serializeStructuredData, homeStructuredData, guideStructuredData } = load(path.join(__dirname, "structured-data.ts"));
const { seoGuides } = load(path.join(__dirname, "seo-guides.ts"));
const { seoGuidePaths } = load(path.join(__dirname, "seo-guide-paths.ts"));

test("JSON-LD의 HTML 닫는 태그를 차단하고 원문을 보존한다", () => {
  const original = { answer: '</script><script>alert("x")</script> & 블러 日本語' };
  const serialized = serializeStructuredData(original);
  assert.ok(!serialized.includes("<"));
  assert.deepEqual(JSON.parse(serialized), original);
});

test("언어·페이지가 달라도 같은 제품과 사이트를 참조한다", () => {
  const graphs = ["ko", "en", "ja"].map((locale) => homeStructuredData(locale)["@graph"]);
  graphs.push(...seoGuides.map((guide) => guideStructuredData(guide)["@graph"]));
  for (const type of ["Organization", "WebSite", "SoftwareApplication"]) {
    assert.equal(new Set(graphs.map((graph) => graph.find((node) => node["@type"] === type)["@id"])).size, 1);
  }
  for (const graph of graphs) {
    assert.ok(!graph.some((node) => "aggregateRating" in node || "offers" in node || "datePublished" in node));
  }
});

test("안내의 경로·언어 탐색·관련 링크가 실제 페이지와 일치한다", () => {
  const paths = seoGuides.map(({ path }) => path);
  assert.equal(new Set(paths).size, paths.length);
  assert.deepEqual([...paths].sort(), [...seoGuidePaths].sort());
  for (const guide of seoGuides) {
    for (const related of guide.related) assert.ok(paths.includes(related), `${guide.path}: ${related}`);
  }
});
