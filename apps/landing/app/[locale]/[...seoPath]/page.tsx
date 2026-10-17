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
  const guide = findSeoGuide(`/${seoPath.join("/")}`, locale === "en" ? "en" : "ko");
  if (!guide) notFound();
  if (locale !== "ko" && locale !== "en") permanentRedirect(`/ko${guide.path}`);
  return { guide, locale: locale as "ko" | "en" };
}

export async function generateMetadata({ params }: Props) {
  const { guide, locale } = await resolveGuide(params);
  return pageMetadata(locale, guide.path, { title: guide.title, description: guide.description });
}

export default async function Page({ params }: Props) {
  return <SeoGuidePage {...await resolveGuide(params)} />;
}
