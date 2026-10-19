import { guideEvidence } from "@/lib/guide-evidence";
import { liveFaceBlurGuide } from "@/lib/live-face-blur-guide";
import { locales, localePath } from "@/lib/locales";
import { pageAlternates, siteOrigin } from "@/lib/seo";
import { seoCopy } from "@/lib/seo-copy";
import { seoGuidePaths, seoGuideLocales } from "@/lib/seo-guide-paths";
import { findSeoGuide } from "@/lib/seo-guides";

function guides() {
  return seoGuideLocales.flatMap((locale) => seoGuidePaths.map((path) => {
    const guide = findSeoGuide(path, locale);
    if (!guide) throw new Error(`Missing search guide: ${locale}${path}`);
    return { locale, guide, url: pageAlternates(locale, path).canonical };
  }));
}

export function llmsDocument(full = false) {
  const lines = ["# InnoLive", "", `> ${seoCopy.en.home.description}`, "",
    "Official product documentation. The linked pages are the source of truth for features, availability and pricing.", "",
    "## Product pages", ...locales.flatMap((locale) => [
      `- [InnoLive (${locale})](${siteOrigin}${localePath(locale, "/")}): ${seoCopy[locale].home.description}`,
      `- [About InnoLive / Framework (${locale})](${siteOrigin}/${locale}/about)`,
      `- [Pricing (${locale})](${siteOrigin}/${locale}/pricing)`,
      `- [Support (${locale})](${siteOrigin}/${locale}/support)`,
      `- [${liveFaceBlurGuide[locale].heading}](${siteOrigin}/${locale}/guides/live-face-blur): ${liveFaceBlurGuide[locale].intro}`,
    ]), "", "## Guides"];
  for (const { locale, guide, url } of guides()) {
    lines.push("", `- [${guide.heading} (${locale})](${url}): ${guide.description}`);
    if (!full) continue;
    lines.push("", guide.intro, "", "Publisher: Framework");
    for (const source of guideEvidence(guide, locale).sources) lines.push(`- [${source.name}](${source.url})`);
    for (const section of guide.sections) lines.push("", `### ${section.heading}`, "", ...section.paragraphs);
    if (guide.comparison) {
      const comparison = guide.comparison;
      lines.push("", comparison.disclosure, `Sources checked: ${comparison.checkedAt}`, comparison.caption,
        comparison.columns.join(" | "),
        ...comparison.rows.map((row) => [row.label, ...row.cells].join(" | ")),
        ...comparison.sources.map((source) => `- [${source.name}](${source.url})`));
    }
    for (const faq of guide.faq ?? []) lines.push("", `### ${faq.question}`, "", faq.answer);
  }
  lines.push("", "## Updates and sources", "",
    "Generated from the same guide content as the public website at each deployment. Dates are included only where the guide records a source check.",
    `- [Full guide text](${siteOrigin}/llms-full.txt)`,
    `- [Blog RSS](${siteOrigin}/feed.xml)`,
    `- [Sitemap](${siteOrigin}/sitemap.xml)`, "");
  return lines.join("\n");
}

function xml(value: string) {
  return value.replace(/[<>&"']/g, (character) => ({ "<": "&lt;", ">": "&gt;", "&": "&amp;", '"': "&quot;", "'": "&apos;" })[character]!);
}

export function blogFeed() {
  const items = guides().filter(({ guide }) => guide.path.startsWith("/blog/"));
  return `<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom"><channel>
<title>InnoLive guides</title><link>${siteOrigin}</link>
<description>InnoLive live face blur guides in Korean, English and Japanese.</description>
<atom:link href="${siteOrigin}/feed.xml" rel="self" type="application/rss+xml"/>
${items.map(({ guide, url }) => `<item><title>${xml(guide.heading)}</title><link>${xml(url)}</link><guid isPermaLink="true">${xml(url)}</guid><description>${xml(guide.intro)}</description></item>`).join("\n")}
</channel></rss>`;
}
