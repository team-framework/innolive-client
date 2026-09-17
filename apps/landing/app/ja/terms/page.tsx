import { DocumentPage } from "@/components/document-page";
import TermsOfService from "@/content/documents/terms-of-service.ja.mdx";
import { documentMetadata } from "@/lib/documents";

export const metadata = documentMetadata("terms", "ja");

export default function JaTermsPage() {
  return <DocumentPage locale="ja" slug="terms" Content={TermsOfService} />;
}
