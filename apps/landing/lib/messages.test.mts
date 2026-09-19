import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

function keys(value: unknown, prefix = ""): string[] {
  if (Array.isArray(value)) {
    return value.flatMap((item, index) => keys(item, `${prefix}[${index}]`));
  }
  if (value && typeof value === "object") {
    return Object.entries(value).flatMap(([key, nested]) =>
      keys(nested, prefix ? `${prefix}.${key}` : key),
    );
  }
  return [prefix];
}

test("한영일 번역 키 동수", () => {
  const dir = join(dirname(fileURLToPath(import.meta.url)), "../messages");
  const korean = keys(JSON.parse(readFileSync(join(dir, "ko.json"), "utf8")));
  const english = keys(JSON.parse(readFileSync(join(dir, "en.json"), "utf8")));
  const japanese = keys(JSON.parse(readFileSync(join(dir, "ja.json"), "utf8")));
  assert.deepEqual(english, korean);
  assert.deepEqual(japanese, korean);
  assert.ok(korean.length > 100);
});
