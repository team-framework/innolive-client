import { TryOutView } from "@/components/try-out-view";
import { defaultLocale, isLocale } from "@/lib/locales";
import { pageMetadata } from "@/lib/seo";
import { seoCopy } from "@/lib/seo-copy";

type PageProps = {
  params: Promise<{ locale: string }>;
};

export async function generateMetadata({ params }: PageProps) {
  const { locale } = await params;
  return pageMetadata(locale, "/try-out", seoCopy[isLocale(locale) ? locale : defaultLocale].tryOut);
}

export default function TryOutPage() {
  return (
    <main id="main" data-page="try-out" className="min-h-dvh bg-background-primary">
      <TryOutView />
    </main>
  );
}
