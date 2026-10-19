/* eslint-disable @typescript-eslint/no-require-imports */
const fs = require("node:fs");
const path = require("node:path");
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
    return load(fs.existsSync(target) ? target : `${target}.ts`);
  };
  vm.runInThisContext(`(function(require, module, exports) {\n${source}\n})`, { filename: file })(localRequire, loadedModule, loadedModule.exports);
  return loadedModule.exports;
}

const { seoGuidePaths, seoGuideLocales } = load(path.join(appRoot, "lib/seo-guide-paths.ts"));
const { findSeoGuide } = load(path.join(appRoot, "lib/seo-guides.ts"));
const { guideEvidence } = load(path.join(appRoot, "lib/guide-evidence.ts"));
const report = process.argv[2] ? JSON.parse(fs.readFileSync(process.argv[2], "utf8")) : null;
const inventory = seoGuideLocales.flatMap(locale => seoGuidePaths.map(guidePath => {
  const guide = findSeoGuide(guidePath, locale);
  const evidence = guideEvidence(guide, locale);
  const url = `https://innolive.studio/${locale}${guidePath}`;
  const metrics = report?.pages?.[url];
  return {
    url,
    locale, title: guide.title, targetQuestion: evidence.question, directAnswer: evidence.answer,
    type: guide.comparison ? "comparison" : guidePath.startsWith("/blog/") ? "article" : "guide",
    sourceCheckedAt: evidence.dataAsOf, firstPublishedAt: null,
    sources: evidence.sources, faqCount: guide.faq?.length ?? 0,
    related: guide.related, status: "published", impressions28d: metrics?.impressions ?? null, clicks28d: metrics?.clicks ?? null,
    measurementNote: metrics ? `Observed ${report.observedAt}; ${report.filter}` : "Unknown until matched with a dated search report; absence from report is not zero.",
  };
}));
process.stdout.write(JSON.stringify(inventory, null, 2) + "\n");
