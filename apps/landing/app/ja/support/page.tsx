import { DocumentPage } from "@/components/document-page";
import Support from "@/content/documents/support.ja.mdx";
import { documentMetadata } from "@/lib/documents";

export const metadata = documentMetadata("support", "ja");

export default function JaSupportPage() {
  return <DocumentPage locale="ja" slug="support" Content={Support} />;
}
