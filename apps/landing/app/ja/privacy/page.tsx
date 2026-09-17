import { DocumentPage } from "@/components/document-page";
import PrivacyPolicy from "@/content/documents/privacy-policy.ja.mdx";
import { documentMetadata } from "@/lib/documents";

export const metadata = documentMetadata("privacy", "ja");

export default function JaPrivacyPage() {
  return <DocumentPage locale="ja" slug="privacy" Content={PrivacyPolicy} />;
}
