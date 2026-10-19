import { notFound } from "next/navigation";
import { SeoGuidePage } from "@/components/seo-guide-page";
import { findSeoGuide, seoGuides } from "@/lib/seo-guides";
import { isLocale } from "@/lib/locales";
import { pageMetadata } from "@/lib/seo";

type Props = { params: Promise<{ locale: string; seoPath: string[] }> };

export const dynamicParams = false;

export function generateStaticParams() {
  return seoGuides.map(({ path }) => ({ seoPath: path.slice(1).split("/") }));
}

async function resolveGuide(params: Props["params"]) {
  const { locale, seoPath } = await params;
  if (!isLocale(locale)) notFound();
  const guide = findSeoGuide(`/${seoPath.join("/")}`, locale);
  if (!guide) notFound();
  return { guide, locale };
}

export async function generateMetadata({ params }: Props) {
  const { guide, locale } = await resolveGuide(params);
  return pageMetadata(locale, guide.path, { title: guide.title, description: guide.description });
}

export default async function Page({ params }: Props) {
  return <SeoGuidePage {...await resolveGuide(params)} />;
}
