import Link from "next/link";
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
          {locale === "ko" && (
            <section aria-labelledby="mosaic-guides-heading" className="mx-auto flex max-w-[72rem] flex-col gap-6 px-[var(--page-gutter)] py-16">
              <h2 id="mosaic-guides-heading" className="text-heading">라이브 모자이크와 얼굴 비식별화</h2>
              <p className="text-body leading-relaxed">InnoLive는 야외 라이브 방송에서 공개 대상자로 등록한 얼굴을 보여 주고 행인의 얼굴을 실시간 블러로 가립니다. 무료 이용 범위, 휴대폰 방송 준비, 유튜브·치지직 송출 방법을 확인하세요.</p>
              <div className="flex flex-wrap gap-x-8 gap-y-4 text-body leading-relaxed">
                <Link href="/ko/live-mosaic" className="underline">실시간 자동 모자이크 자세히 보기</Link>
                <Link href="/ko/face-mosaic" className="underline">얼굴 자동 모자이크 기능 확인</Link>
                <Link href="/ko/blog/live-face-blur" className="underline">라이브 방송에서 행인 얼굴 가리는 방법</Link>
                <Link href="/ko/free-face-blur" className="underline">무료 자동 모자이크 이용 범위</Link>
                <Link href="/ko/mobile-live-face-blur" className="underline">휴대폰 야외방송 얼굴 가리기</Link>
                <Link href="/ko/obs-face-blur" className="underline">OBS 자동 모자이크와 사용 범위</Link>
              </div>
            </section>
          )}
          <FAQSection />
          <Footer />
        </SmoothScroll>
      </main>
    </>
  );
}
