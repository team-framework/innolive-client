import Image from "next/image";
import Link from "next/link";
import { Button } from "@/components/button";
import { findSeoGuide, type SeoGuide } from "@/lib/seo-guides";
import { StructuredData } from "@/components/structured-data";
import { guideStructuredData } from "@/lib/structured-data";

export function SeoGuidePage({ guide }: { guide: SeoGuide }) {
  return (
    <main id="main" tabIndex={-1} className="min-h-dvh bg-background-primary px-[var(--page-gutter)] pb-20 pt-40 md:pt-56">
      <StructuredData data={guideStructuredData(guide)} />
      <article className="mx-auto flex max-w-[72rem] min-w-0 flex-col gap-12 break-keep">
        <nav aria-label="현재 위치" className="flex flex-wrap gap-3 text-base leading-relaxed text-text-secondary">
          <Link href="/ko" className="underline">InnoLive</Link>
          <span aria-hidden="true">/</span>
          <span aria-current="page">{guide.heading}</span>
        </nav>
        <div className="flex flex-col gap-6">
          <h1 className="text-heading font-bold leading-snug">{guide.heading}</h1>
          <p className="max-w-[60rem] text-body leading-relaxed">{guide.intro}</p>
          <div className="flex flex-wrap gap-3">
            <Button href="/ko/try-out" variant="secondary" showChevron={false}>얼굴 가리기 체험하기</Button>
            <Button href="/ko/pricing" showChevron={false}>방송 요금제 확인</Button>
          </div>
        </div>
        {guide.path === "/live-mosaic" && (
          <figure className="flex flex-col gap-4">
            <Image src="/mockups/hero-face-registration.webp" alt="InnoLive에서 방송에 공개할 얼굴을 등록하는 화면" width={640} height={960} className="h-auto max-h-[32rem] w-full rounded-card bg-background-secondary object-contain" />
            <figcaption className="text-base leading-relaxed text-text-secondary">공개할 얼굴을 등록하고, 방송 전에 다른 얼굴의 블러 처리 결과를 확인하세요.</figcaption>
          </figure>
        )}
        {guide.sections.map((section) => (
          <section key={section.heading} className="flex flex-col gap-4">
            <h2 className="text-2xl font-semibold leading-snug md:text-3xl">{section.heading}</h2>
            {section.paragraphs.map((paragraph) => <p key={paragraph} className="text-body leading-relaxed">{paragraph}</p>)}
          </section>
        ))}
        {guide.faq && (
          <section id="faq" className="flex flex-col gap-6">
            <h2 className="text-2xl font-semibold md:text-3xl">자주 묻는 질문</h2>
            {guide.faq.map(({ question, answer }, index) => (
              <div id={`faq-${index + 1}`} key={question} className="flex flex-col gap-3">
                <h3 className="text-xl font-semibold leading-snug">{question}</h3>
                <p className="text-body leading-relaxed">{answer}</p>
              </div>
            ))}
          </section>
        )}
        <nav aria-label="관련 기능과 사용 방법" className="flex flex-col gap-4 border-t border-surface-primary pt-8">
          <h2 className="text-2xl font-semibold">관련 기능과 사용 방법</h2>
          {guide.related.map((path) => {
            const related = findSeoGuide(path);
            return related && <Link key={path} href={`/ko${path}`} className="text-body leading-relaxed underline underline-offset-4">{related.heading}</Link>;
          })}
        </nav>
      </article>
    </main>
  );
}
