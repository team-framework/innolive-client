import assert from "node:assert/strict";
import test from "node:test";
import {
  interpolate,
  isLocale,
  localePath,
  stripLocalePrefix,
  switchLocalePath,
} from "./locales.ts";

test("지원 로케일과 경로 접두사", () => {
  assert.equal(isLocale("ko"), true);
  assert.equal(isLocale("en"), true);
  assert.equal(isLocale("ja"), true);
  assert.equal(isLocale("fr"), false);

  assert.equal(localePath("ko", "/"), "/ko");
  assert.equal(localePath("en", "/pricing"), "/en/pricing");
  assert.equal(localePath("ja", "/#faq"), "/ja/#faq");
  assert.equal(localePath("ko", "/try-out?x=1#main"), "/ko/try-out?x=1#main");

  assert.equal(stripLocalePrefix("/en/pricing"), "/pricing");
  assert.equal(stripLocalePrefix("/ja"), "/");
  assert.equal(stripLocalePrefix("/privacy"), "/privacy");

  assert.equal(switchLocalePath("/ko/pricing", "en"), "/en/pricing");
  assert.equal(switchLocalePath("/en", "ja", "", "#faq"), "/ja/#faq");
  assert.equal(interpolate("{name} 링크는 아직 없습니다", { name: "GitHub" }), "GitHub 링크는 아직 없습니다");
});
