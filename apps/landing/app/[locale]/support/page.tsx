import { notFound } from "next/navigation";
import { DocumentPage } from "@/components/document-page";
import { documentContent } from "@/lib/document-content";
import { documentMetadata } from "@/lib/documents";
import { isLocale } from "@/lib/locales";

type PageProps = {
  params: Promise<{ locale: string }>;
};

export async function generateMetadata({ params }: PageProps) {
  const { locale } = await params;
  if (!isLocale(locale)) return {};
  return documentMetadata("support", locale);
}

export default async function SupportPage({ params }: PageProps) {
  const { locale } = await params;
  if (!isLocale(locale)) notFound();
  return (
    <DocumentPage
      locale={locale}
      slug="support"
      Content={documentContent.support[locale]}
    />
  );
}
