import type { Metadata } from "next";
import { DocumentPage } from "@/components/document-page";
import Support from "@/content/documents/support.ko.mdx";
import { documentTitles } from "@/lib/documents";

export const metadata: Metadata = {
  title: documentTitles.support.ko,
};

export default function SupportPage() {
  return (
    <DocumentPage locale="ko">
      <Support />
    </DocumentPage>
  );
}
