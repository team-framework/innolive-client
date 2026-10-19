import { createRequire } from "node:module";
import { createHash } from "node:crypto";
import path from "node:path";
import fs from "node:fs/promises";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const sharp = createRequire(require.resolve("next/package.json"))("sharp");
const publicDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../public");
const sources = ["hero-phone.webp", "youtube-logo.png", "chzzk-logo.png"];
const outputDir = path.join(publicDir, "landing/optimized");
await fs.mkdir(outputDir, { recursive: true });
const manifest = {};

for (const source of sources) {
  const input = path.join(publicDir, "landing", source);
  const name = path.parse(source).name;
  const { width, height } = await sharp(input).metadata();
  const widths = [...new Set([256, 384, 640, 1080, width].filter((value) => value <= width))];
  const variants = [];
  for (const outputWidth of widths) {
    const buffer = await sharp(input).resize({ width: outputWidth })
      .webp({ quality: 75, effort: 6 }).toBuffer();
    const hash = createHash("sha256").update(buffer).digest("hex").slice(0, 12);
    const filename = `${name}-${outputWidth}-${hash}.webp`;
    await fs.writeFile(path.join(outputDir, filename), buffer);
    variants.push({ width: outputWidth, src: `/landing/optimized/${filename}` });
    console.log(`${filename}: ${buffer.length} bytes`);
  }
  manifest[name] = {
    width,
    height,
    src: variants.find((variant) => variant.width === 640).src,
    srcSet: variants.map((variant) => `${variant.src} ${variant.width}w`).join(", "),
  };
}
await fs.writeFile(path.join(publicDir, "../lib/static-artwork.json"), `${JSON.stringify(manifest, null, 2)}\n`);
