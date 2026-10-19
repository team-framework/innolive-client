import { guideEvidence } from "@/lib/guide-evidence";
import { aboutCopy } from "@/lib/about";
import { getHomeFaq, type FaqItem } from "@/lib/faq";
import { localePath, locales, type Locale } from "@/lib/locales";
import { getMessages } from "@/lib/messages";
import { siteOrigin } from "@/lib/seo";
import { downloadPlatforms, socialLinks } from "@/lib/site";
import { seoCopy } from "@/lib/seo-copy";
import type { SeoGuide } from "@/lib/seo-guides";

const organizationId = `${siteOrigin}/#organization`;
const websiteId = `${siteOrigin}/#website`;
const softwareId = `${siteOrigin}/#software`;

// Escape HTML delimiters without changing the strings parsed from the JSON.
export function serializeStructuredData(data: unknown): string {
  return JSON.stringify(data).replace(/</g, "\\u003c");
}

function siteEntities(locale: Locale) {
  return [
    {
      "@type": "Organization", "@id": organizationId,
      name: "Framework", url: `${siteOrigin}/${locale}/about`,
      logo: `${siteOrigin}/brand/logo-header.svg`,
      sameAs: socialLinks.map(({ href }) => href),
    },
    {
      "@type": "WebSite", "@id": websiteId,
      name: "InnoLive", url: siteOrigin, inLanguage: [...locales],
      publisher: { "@id": organizationId },
    },
    {
      "@type": "SoftwareApplication", "@id": softwareId,
      name: "InnoLive", alternateName: "이노라이브", applicationCategory: "MultimediaApplication",
      url: `${siteOrigin}${localePath(locale, "/")}`,
      description: seoCopy[locale].home.description,
      operatingSystem: downloadPlatforms.map((platform) => platform.minOs),
      installUrl: downloadPlatforms.flatMap((platform) => platform.href ? [platform.href] : []),
      publisher: { "@id": organizationId },
    },
  ];
}

function faqEntity(url: string, locale: Locale, items: FaqItem[]) {
  return {
    "@type": "FAQPage", "@id": `${url}#faq`, url: `${url}#faq`,
    inLanguage: locale, isPartOf: { "@id": `${url}#webpage` },
    mainEntity: items.map(({ question, answer }, index) => ({
      "@type": "Question", "@id": `${url}#faq-${index + 1}`, name: question,
      acceptedAnswer: { "@type": "Answer", text: answer },
    })),
  };
}

export function homeStructuredData(locale: Locale) {
  const url = `${siteOrigin}${localePath(locale, "/")}`;
  return {
    "@context": "https://schema.org",
    "@graph": [
      ...siteEntities(locale),
      {
        "@type": "WebPage", "@id": `${url}#webpage`, url,
        name: seoCopy[locale].home.title,
        description: getMessages(locale).hero.support1, inLanguage: locale,
        isPartOf: { "@id": websiteId }, mainEntity: { "@id": softwareId },
        hasPart: { "@id": `${url}#faq` },
      },
      faqEntity(url, locale, getHomeFaq(locale)),
    ],
  };
}

export function guideStructuredData(guide: SeoGuide, locale: Locale = "ko") {
  const url = `${siteOrigin}/${locale}${guide.path}`;
  const isArticle = guide.path.startsWith("/blog/");
  const faq = guide.faq ?? [];
  const evidence = guideEvidence(guide, locale);
  return {
    "@context": "https://schema.org",
    "@graph": [
      ...siteEntities(locale),
      {
        "@type": "WebPage", "@id": `${url}#webpage`, url,
        name: guide.heading, description: guide.intro, inLanguage: locale,
        isPartOf: { "@id": websiteId }, about: { "@id": softwareId },
        breadcrumb: { "@id": `${url}#breadcrumb` },
        ...(guide.comparison ? {
          author: { "@id": organizationId },
          dateModified: guide.comparison.checkedAt,
          citation: guide.comparison.sources.map(({ url }) => url),
        } : {}),
        ...(guide.path === "/mosaic-software" ? { mainEntity: { "@id": softwareId } } : {}),
        ...(isArticle ? { mainEntity: { "@id": `${url}#article` } } : {}),
        ...(faq.length ? { hasPart: { "@id": `${url}#faq` } } : {}),
      },
      {
        "@type": "BreadcrumbList", "@id": `${url}#breadcrumb`,
        itemListElement: [
          { "@type": "ListItem", position: 1, name: "InnoLive", item: `${siteOrigin}/${locale}` },
          { "@type": "ListItem", position: 2, name: guide.heading, item: url },
        ],
      },
      ...(isArticle ? [{
        "@type": "Article", "@id": `${url}#article`, headline: guide.heading,
        publisher: { "@id": organizationId },
        author: { "@id": organizationId },
        citation: evidence.sources.map(({ url }) => url),
        description: guide.intro, inLanguage: locale,
        mainEntityOfPage: { "@id": `${url}#webpage` }, about: { "@id": softwareId },
      }] : []),
      ...(faq.length ? [faqEntity(url, locale, faq)] : []),
    ],
  };
}

export function overviewStructuredData(locale: Locale, guide: { heading: string; intro: string }) {
  const url = `${siteOrigin}/${locale}/guides/live-face-blur`;
  return {
    "@context": "https://schema.org",
    "@graph": [
      ...siteEntities(locale),
      {
        "@type": "WebPage", "@id": `${url}#webpage`, url,
        name: guide.heading, description: guide.intro, inLanguage: locale,
        isPartOf: { "@id": websiteId }, about: { "@id": softwareId },
        publisher: { "@id": organizationId },
      },
    ],
  };
}

export function aboutStructuredData(locale: Locale) {
  const url = `${siteOrigin}/${locale}/about`;
  return {
    "@context": "https://schema.org",
    "@graph": [...siteEntities(locale), {
      "@type": "AboutPage", "@id": `${url}#webpage`, url,
      name: aboutCopy[locale].heading, description: aboutCopy[locale].intro,
      inLanguage: locale, isPartOf: { "@id": websiteId },
      mainEntity: { "@id": softwareId }, publisher: { "@id": organizationId },
    }],
  };
}
