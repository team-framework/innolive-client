import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { Footer } from "@/components/footer";
import { Header } from "@/components/header";
import { LocaleProvider } from "@/components/locale-provider";
import { RouteScrollReset } from "@/components/route-scroll-reset";
import { defaultLocale, isLocale, locales } from "@/lib/locales";
import { getMessages } from "@/lib/messages";
import { seoCopy } from "@/lib/seo-copy";
import { siteOrigin } from "@/lib/seo";
import { wantedSans, wantedSansCommonPreload } from "../fonts";
import "../globals.css";
import "../fonts/wanted-sans.css";

type LocaleLayoutProps = Readonly<{
  children: React.ReactNode;
  params: Promise<{ locale: string }>;
}>;

export const dynamicParams = false;

export function generateStaticParams() {
  return locales.map((locale) => ({ locale }));
}

export async function generateMetadata({
  params,
}: LocaleLayoutProps): Promise<Metadata> {
  const { locale } = await params;
  return {
    metadataBase: new URL(siteOrigin),
    verification: {
      google: "J-BWZsWvCgeHZuOUFzYDyXDBIqTkKECI0MxWfgG1Uzo",
      other: { "naver-site-verification": "cbbd23aaf2e26c9acc29f017f3809f4b5badf6bc" },
    },
    ...seoCopy[isLocale(locale) ? locale : defaultLocale].home,
  };
}

export default async function LocaleLayout({
  children,
  params,
}: LocaleLayoutProps) {
  const { locale } = await params;
  if (!isLocale(locale)) notFound();
  const messages = getMessages(locale);

  return (
    <html lang={locale} className={`${wantedSans.variable} ${wantedSans.className}`}>
      <head>
        {locale !== "en" && (
          <link
            rel="preload"
            href={wantedSansCommonPreload}
            as="font"
            type="font/woff2"
            crossOrigin="anonymous"
          />
        )}
      </head>
      <body className="flex min-h-dvh flex-col">
        <LocaleProvider locale={locale} messages={messages}>
          <RouteScrollReset />
          <Header />
          <div className="flex-1">{children}</div>
          <Footer />
        </LocaleProvider>
      </body>
    </html>
  );
}
