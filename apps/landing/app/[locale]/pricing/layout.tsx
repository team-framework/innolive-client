import type { ReactNode } from "react";
import { getMessages } from "@/lib/messages";

type LayoutProps = {
  children: ReactNode;
  params: Promise<{ locale: string }>;
};

export async function generateMetadata({ params }: LayoutProps) {
  const { locale } = await params;
  return { title: getMessages(locale).metadata.pricingTitle };
}

export default function PricingLayout({ children }: LayoutProps) {
  return children;
}
