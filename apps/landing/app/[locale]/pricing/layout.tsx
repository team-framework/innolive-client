import type { ReactNode } from "react";
import { defaultLocale, isLocale } from "@/lib/locales";
import { pageMetadata } from "@/lib/seo";
import { seoCopy } from "@/lib/seo-copy";

type LayoutProps = {
  children: ReactNode;
  params: Promise<{ locale: string }>;
};

export async function generateMetadata({ params }: LayoutProps) {
  const { locale } = await params;
  return pageMetadata(locale, "/pricing", seoCopy[isLocale(locale) ? locale : defaultLocale].pricing);
}

export default function PricingLayout({ children }: LayoutProps) {
  return children;
}
