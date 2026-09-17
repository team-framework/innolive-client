import { DocumentPage } from "@/components/document-page";
import TermsOfService from "@/content/documents/terms-of-service.en.mdx";
import { documentMetadata } from "@/lib/documents";

export const metadata = documentMetadata("terms", "en");

export default function EnTermsPage() {
  return <DocumentPage locale="en" slug="terms" Content={TermsOfService} />;
}
