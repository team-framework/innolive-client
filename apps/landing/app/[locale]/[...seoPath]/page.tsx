import { notFound, permanentRedirect } from "next/navigation";
import { SeoGuidePage } from "@/components/seo-guide-page";
import { findSeoGuide, seoGuides } from "@/lib/seo-guides";
import { pageMetadata } from "@/lib/seo";

type Props = { params: Promise<{ locale: string; seoPath: string[] }> };

export const dynamicParams = false;

export function generateStaticParams() {
  return seoGuides.map(({ path }) => ({ seoPath: path.slice(1).split("/") }));
}

async function resolveGuide(params: Props["params"]) {
  const { locale, seoPath } = await params;
  const guide = findSeoGuide(`/${seoPath.join("/")}`);
  if (!guide) notFound();
  if (locale !== "ko") permanentRedirect(`/ko${guide.path}`);
  return guide;
}

export async function generateMetadata({ params }: Props) {
  const guide = await resolveGuide(params);
  const metadata = pageMetadata("ko", guide.path, { title: guide.title, description: guide.description });
  return {
    ...metadata,
    alternates: { canonical: metadata.alternates?.canonical },
    openGraph: { ...metadata.openGraph, alternateLocale: [] },
  };
}

export default async function Page({ params }: Props) {
  return <SeoGuidePage guide={await resolveGuide(params)} />;
}
