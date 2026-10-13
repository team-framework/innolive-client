import { FAQSection } from "@/components/faq-section";
import { FeatureSection } from "@/components/feature-section";
import { Footer } from "@/components/footer";
import { Header } from "@/components/header";
import { Hero } from "@/components/hero";
import { IntroScroll } from "@/components/intro-scroll";
import { PrivacySection } from "@/components/privacy-section";
import {SmoothScroll} from "@/components/smooth-wrapper";
import { notFound } from "next/navigation";
import { defaultLocale, isLocale } from "@/lib/locales";
import { pageMetadata } from "@/lib/seo";
import { seoCopy } from "@/lib/seo-copy";
import { StructuredData } from "@/components/structured-data";
import { homeStructuredData } from "@/lib/structured-data";

type PageProps = { params: Promise<{ locale: string }> };

export async function generateMetadata({ params }: PageProps) {
  const { locale } = await params;
  return pageMetadata(locale, "/", seoCopy[isLocale(locale) ? locale : defaultLocale].home);
}

export default async function Home({ params }: PageProps) {
  const { locale } = await params;
  if (!isLocale(locale)) notFound();
  return (
    <>
      <StructuredData data={homeStructuredData(locale)} />
      <IntroScroll />
      <Header />
      <main
        id="main"
        tabIndex={-1}
        data-page="home"
        className="bg-background-primary"
      >
        <SmoothScroll>
          <Hero />
          <PrivacySection />
          <FeatureSection />
          <FAQSection />
          <Footer />
        </SmoothScroll>
      </main>
    </>
  );
}
