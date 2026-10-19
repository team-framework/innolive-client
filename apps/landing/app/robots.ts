import type { MetadataRoute } from "next";
import { siteOrigin } from "@/lib/seo";

export default function robots(): MetadataRoute.Robots {
  return {
    // Explicit groups repeat the API restriction: specific groups do not inherit '*'.
    rules: ["*", "Googlebot", "Bingbot", "Yeti", "OAI-SearchBot", "ChatGPT-User",
      "GPTBot", "ClaudeBot", "Claude-SearchBot", "Claude-User", "PerplexityBot",
      "Perplexity-User", "Google-Extended", "CCBot", "Applebot-Extended"].map((userAgent) => ({
      userAgent, allow: "/", disallow: "/api/",
    })),
    sitemap: `${siteOrigin}/sitemap.xml`,
  };
}
