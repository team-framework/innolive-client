import type { Metadata } from "next";
import { DocumentPage } from "@/components/document-page";
import PrivacyPolicy from "@/content/documents/privacy-policy.ko.mdx";
import { documentTitles } from "@/lib/documents";

export const metadata: Metadata = {
  title: documentTitles.privacy.ko,
};

export default function PrivacyPage() {
  return (
    <DocumentPage locale="ko">
      <PrivacyPolicy />
    </DocumentPage>
  );
}
