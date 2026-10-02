import type { MetadataRoute } from "next";
import { locales } from "@/lib/locales";
import { pageAlternates, sitemapPaths } from "@/lib/seo";

export default function sitemap(): MetadataRoute.Sitemap {
  return sitemapPaths.flatMap((path) => locales.map((locale) => {
    const alternates = pageAlternates(locale, path);
    return {
      url: alternates.canonical,
      alternates: { languages: alternates.languages },
    };
  }));
}
