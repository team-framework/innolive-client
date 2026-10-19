import { createRequire } from "node:module";
import path from "node:path";
import fs from "node:fs/promises";
import { fileURLToPath } from "node:url";

// Use the image encoder shipped with this app's Next.js dependency.
const appRequire = createRequire(import.meta.url);
const nextRequire = createRequire(appRequire.resolve("next/package.json"));
const sharp = nextRequire("sharp");
const publicDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../public");
const assets = [
  ["mockups/hero-face-registration.png", false],
  ["mockups/privacy-1.png", false],
  ["mockups/privacy-2.png", false],
  ["mockups/privacy-3.png", false],
  ["landing/hero-phone.png", true],
  ["landing/privacy-phone.png", true],
];

async function main() {
  for (const [source, lossless] of assets) {
    const input = path.join(publicDir, source);
    const output = input.replace(/\.png$/, ".webp");
    await sharp(input).webp({ quality: 90, lossless, effort: 6 }).toFile(output);
    const [before, after] = await Promise.all([fs.stat(input), fs.stat(output)]);
    console.log(`${source}: ${before.size} → ${after.size} bytes`);
  }
  await sharp(path.join(publicDir, "mockups/hero-live-demo.jpg"))
    .webp({ quality: 80, effort: 6 })
    .toFile(path.join(publicDir, "mockups/hero-live-demo.webp"));

}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
