import { llmsDocument } from "@/lib/search-documents";

export const dynamic = "force-static";

export function GET() {
  return new Response(llmsDocument(), { headers: { "Content-Type": "text/plain; charset=utf-8" } });
}
