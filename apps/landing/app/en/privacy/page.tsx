import { DocumentPage } from "@/components/document-page";
import PrivacyPolicy from "@/content/documents/privacy-policy.en.mdx";
import { documentMetadata } from "@/lib/documents";

export const metadata = documentMetadata("privacy", "en");

export default function EnPrivacyPage() {
  return <DocumentPage locale="en" slug="privacy" Content={PrivacyPolicy} />;
}
