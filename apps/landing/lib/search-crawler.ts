import { isLocale } from "@/lib/locales";
import { isSeoGuidePath } from "@/lib/seo-guide-paths";
import { sitemapPaths } from "@/lib/seo";

const crawlerNames = ["Googlebot", "bingbot", "Yeti", "GPTBot", "OAI-SearchBot", "ChatGPT-User", "PerplexityBot", "Perplexity-User", "ClaudeBot", "Claude-User", "Claude-SearchBot", "Applebot"] as const;
const documentPaths = new Set(["/robots.txt", "/sitemap.xml", "/llms.txt", "/llms-full.txt", "/feed.xml"]);

// Self-reported User-Agent signals, not verified crawler identities.
// Never retain raw URLs, query strings, IPs, cookies, or request headers.
export function searchCrawlerEvent(pathname: string, method: string, userAgent: string | null) {
  if (method !== "GET" && method !== "HEAD") return null;
  const crawler = crawlerNames.find(name => new RegExp(`\\b${name}\\b`, "i").test(userAgent ?? ""));
  if (!crawler) return null;
  const [, locale, ...parts] = pathname.split("/");
  const pagePath = `/${parts.join("/")}`;
  const isPublicPage = isLocale(locale) && (sitemapPaths.some(path => path === pagePath) || isSeoGuidePath(pagePath));
  if (!documentPaths.has(pathname) && !isPublicPage) return null;
  return { event: "search_crawler_request", crawler, path: pathname, method };
}
