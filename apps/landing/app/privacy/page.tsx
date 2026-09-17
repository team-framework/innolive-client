import { DocumentPage } from "@/components/document-page";
import PrivacyPolicy from "@/content/documents/privacy-policy.ko.mdx";
import { documentMetadata } from "@/lib/documents";

export const metadata = documentMetadata("privacy", "ko");

export default function PrivacyPage() {
  return <DocumentPage locale="ko" slug="privacy" Content={PrivacyPolicy} />;
}
