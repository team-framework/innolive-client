import { DocumentPage } from "@/components/document-page";
import TermsOfService from "@/content/documents/terms-of-service.ko.mdx";
import { documentMetadata } from "@/lib/documents";

export const metadata = documentMetadata("terms", "ko");

export default function TermsPage() {
  return <DocumentPage locale="ko" slug="terms" Content={TermsOfService} />;
}
