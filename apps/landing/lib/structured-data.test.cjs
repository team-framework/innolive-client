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
    return load(fs.existsSync(target) ? target : `${target}.ts`);
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

const { englishSeoGuides } = load(path.join(__dirname, "seo-guides.en.ts"));
const { pageAlternates } = load(path.join(__dirname, "seo.ts"));
test("English guides have complete routes, localized schema and reciprocal alternates", () => {
  assert.deepEqual(englishSeoGuides.map(g => g.path).sort(), seoGuides.map(g => g.path).sort());
  for (const guide of englishSeoGuides) {
    const korean = seoGuides.find(g => g.path === guide.path);
    assert.equal(guide.sections.length, korean.sections.length);
    assert.equal(guide.faq?.length ?? 0, korean.faq?.length ?? 0);
    assert.ok(!/[가-힣]/.test(JSON.stringify(guide)));
    for (const related of guide.related) assert.ok(englishSeoGuides.some(g => g.path === related));
    const graph = guideStructuredData(guide, "en")["@graph"];
    const page = graph.find(n => n["@type"] === "WebPage");
    assert.equal(page.inLanguage, "en");
    assert.equal(page.url, `https://innolive.studio/en${guide.path}`);
    assert.equal(page.description, guide.intro);
    for (const node of graph.filter(n => n.inLanguage && n["@type"] !== "WebSite")) assert.equal(node.inLanguage, "en");
    const en = pageAlternates("en", guide.path);
    assert.equal(en.canonical, page.url);
    assert.deepEqual(en.languages, pageAlternates("ko", guide.path).languages);
    assert.deepEqual(Object.keys(en.languages).sort(), ["en", "ja", "ko", "x-default"]);
  }
});

const { japaneseSeoGuides } = load(path.join(__dirname, "seo-guides.ja.ts"));
const { findSeoGuide } = load(path.join(__dirname, "seo-guides.ts"));
test("Japanese guides cover every route, section, FAQ and related link", () => {
  assert.deepEqual(japaneseSeoGuides.map(g => g.path).sort(), [...seoGuidePaths].sort());
  for (const guide of japaneseSeoGuides) {
    const english = englishSeoGuides.find(g => g.path === guide.path);
    assert.equal(guide.sections.length, english.sections.length);
    assert.deepEqual(guide.sections.map(s => s.paragraphs.length), english.sections.map(s => s.paragraphs.length));
    assert.equal(guide.faq?.length ?? 0, english.faq?.length ?? 0);
    assert.ok(!/[가-힣]/.test(JSON.stringify(guide)));
    assert.match(guide.heading, /[ぁ-んァ-ヶ一-龯]/);
    assert.equal(findSeoGuide(guide.path, "ja"), guide);
    for (const related of guide.related) assert.ok(findSeoGuide(related, "ja"));
    const graph = guideStructuredData(guide, "ja")["@graph"];
    const page = graph.find(n => n["@type"] === "WebPage");
    assert.equal(page.inLanguage, "ja");
    assert.equal(page.url, `https://innolive.studio/ja${guide.path}`);
    assert.equal(page.description, guide.intro);
    const alternates = pageAlternates("ja", guide.path);
    assert.equal(alternates.canonical, page.url);
    assert.equal(alternates.languages.ja, page.url);
    assert.deepEqual(alternates.languages, pageAlternates("ko", guide.path).languages);
  }
});

const { llmsDocument, blogFeed } = load(path.join(__dirname, "search-documents.ts"));

const { socialLinks } = load(path.join(__dirname, "site.ts"));
const { overviewStructuredData } = load(path.join(__dirname, "structured-data.ts"));
const { liveFaceBlurGuide } = load(path.join(__dirname, "live-face-blur-guide.ts"));

test("AI 안내와 전문은 모든 언어의 가이드 및 FAQ 원문을 보존한다", () => {
  const index = llmsDocument();
  const full = llmsDocument(true);
  for (const locale of ["ko", "en", "ja"]) {
    for (const guidePath of seoGuidePaths) {
      const guide = findSeoGuide(guidePath, locale);
      assert.ok(index.includes(`https://innolive.studio/${locale}${guidePath}`));
      assert.ok(full.includes(guide.intro));
      for (const faq of guide.faq ?? []) {
        assert.ok(full.includes(faq.question));
        assert.ok(full.includes(faq.answer));
      }
    }
    const overview = overviewStructuredData(locale, liveFaceBlurGuide[locale])["@graph"];
    const page = overview.find(node => node["@type"] === "WebPage");
    assert.equal(page.description, liveFaceBlurGuide[locale].intro);
    assert.equal(page.inLanguage, locale);
  }
});

test("공식 소셜·블로그 발행자 연결과 RSS가 기존 공개 콘텐츠에서 생성된다", () => {
  const graph = homeStructuredData("ko")["@graph"];
  const organization = graph.find(node => node["@type"] === "Organization");
  assert.deepEqual(organization.sameAs, socialLinks.map(link => link.href));
  const blog = seoGuides.find(guide => guide.path.startsWith("/blog/"));
  const article = guideStructuredData(blog)["@graph"].find(node => node["@type"] === "Article");
  assert.equal(article.publisher["@id"], organization["@id"]);
  const feed = blogFeed();
  assert.equal((feed.match(/<item>/g) ?? []).length, seoGuides.filter(guide => guide.path.startsWith("/blog/")).length * 3);
  assert.ok(!feed.includes("<pubDate>"), "발행일 근거 없이 날짜를 만들지 않는다");
});

const { aboutStructuredData } = load(path.join(__dirname, "structured-data.ts"));
const { aboutCopy } = load(path.join(__dirname, "about.ts"));
const { pageMetadata } = load(path.join(__dirname, "seo.ts"));
test("소개 페이지는 제품과 운영팀을 구분하고 계정 양식만 색인에서 제외한다", () => {
  for (const locale of ["ko", "en", "ja"]) {
    const graph = aboutStructuredData(locale)["@graph"];
    assert.equal(graph.find(node => node["@type"] === "Organization").name, "Framework");
    assert.equal(graph.find(node => node["@type"] === "SoftwareApplication").name, "InnoLive");
    assert.equal(graph.find(node => node["@type"] === "AboutPage").description, aboutCopy[locale].intro);
    for (const route of ["/login", "/signup"]) assert.equal(pageMetadata(locale, route, aboutCopy[locale]).robots.index, false);
    assert.equal(pageMetadata(locale, "/about", aboutCopy[locale]).robots, undefined);
  }
});
