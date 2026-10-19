import { blogFeed } from "@/lib/search-documents";

export const dynamic = "force-static";

export function GET() {
  return new Response(blogFeed(), { headers: { "Content-Type": "application/rss+xml; charset=utf-8" } });
}
