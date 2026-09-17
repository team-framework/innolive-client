import { DocumentPage } from "@/components/document-page";
import Support from "@/content/documents/support.ko.mdx";
import { documentMetadata } from "@/lib/documents";

export const metadata = documentMetadata("support", "ko");

export default function SupportPage() {
  return <DocumentPage locale="ko" slug="support" Content={Support} />;
}
