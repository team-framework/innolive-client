import { DocumentPage } from "@/components/document-page";
import Support from "@/content/documents/support.en.mdx";
import { documentMetadata } from "@/lib/documents";

export const metadata = documentMetadata("support", "en");

export default function EnSupportPage() {
  return <DocumentPage locale="en" slug="support" Content={Support} />;
}
