import Image from "next/image";
import Link from "next/link";
import { Button } from "@/components/button";
import { findSeoGuide, type SeoGuide } from "@/lib/seo-guides";
import { StructuredData } from "@/components/structured-data";
import { guideStructuredData } from "@/lib/structured-data";
import { getMessages } from "@/lib/messages";
import { downloadPlatforms } from "@/lib/site";
import { getPersonalPlans } from "@/lib/plans";

export function SeoGuidePage({ guide, locale = "ko" }: { guide: SeoGuide; locale?: "ko" | "en" }) {
  const en = locale === "en";
  const plans = guide.showPersonalPlans ? getPersonalPlans(getMessages(locale), locale) : [];
  return (
    <main id="main" tabIndex={-1} className="page-top-spacing min-h-dvh bg-background-primary px-[var(--page-gutter)] pb-20">
      <StructuredData data={guideStructuredData(guide, locale)} />
      <article className="mx-auto flex max-w-[72rem] min-w-0 flex-col gap-12 break-keep">
        <nav aria-label={en ? "Breadcrumb" : "현재 위치"} className="flex flex-wrap gap-3 text-base leading-relaxed text-text-secondary">
          <Link href={`/${locale}`} className="underline">InnoLive</Link>
          <span aria-hidden="true">/</span>
          <span aria-current="page">{guide.heading}</span>
        </nav>
        <div className="flex flex-col gap-6">
          <h1 className="text-heading font-bold leading-snug">{guide.heading}</h1>
          <p className="max-w-[60rem] text-body leading-relaxed">{guide.intro}</p>
          <div className="flex flex-wrap gap-3">
            <Button href={`/${locale}/try-out`} variant="secondary" showChevron={false}>{en ? "Try face blur" : "얼굴 가리기 체험하기"}</Button>
            <Button href={`/${locale}/pricing`} showChevron={false}>{en ? "View streaming plans" : "방송 요금제 확인"}</Button>
          </div>
        </div>
        {["/live-mosaic", "/face-mosaic", "/mobile-live-face-blur"].includes(guide.path) && (
          <figure className="flex flex-col gap-4">
            <Image src="/mockups/hero-face-registration.webp" alt={en ? "Registering a face to keep visible in an InnoLive broadcast" : "InnoLive에서 방송에 공개할 얼굴을 등록하는 화면"} width={640} height={960} className="h-auto max-h-[32rem] w-full rounded-card bg-background-secondary object-contain" />
            <figcaption className="text-base leading-relaxed text-text-secondary">{en ? "Register the faces you want to show, then check that other faces are blurred before streaming." : "공개할 얼굴을 등록하고, 방송 전에 다른 얼굴의 블러 처리 결과를 확인하세요."}</figcaption>
          </figure>
        )}
        {guide.path === "/mosaic-software" && (
          <section id="install" aria-labelledby="install-heading" className="flex flex-col gap-4 rounded-card bg-background-secondary p-6">
            <h2 id="install-heading" className="text-2xl font-semibold md:text-3xl">{en ? "Install the InnoLive broadcasting app" : "InnoLive 방송 앱 설치"}</h2>
            <p className="text-body leading-relaxed">{en ? "Stream live camera video with automatic face blur to YouTube or CHZZK. Register guests to keep them visible and check the result before broadcasting." : "카메라 영상의 얼굴을 자동으로 블러 처리하고 유튜브·치지직(CHZZK)으로 실시간 송출하세요. 공개할 출연자를 등록하고 방송 전에 처리 결과를 확인하세요."}</p>
            <ul className="flex flex-col gap-3 text-body leading-relaxed">
              {downloadPlatforms.map((platform) => (
                <li key={platform.id}>
                  <span>{platform.name} · {platform.minOs} · </span>
                  {platform.href ? <a href={platform.href} className="underline underline-offset-4">{platform.id === "ios" ? "App Store" : "ONE store"}{en ? " download" : "에서 설치"}</a> : <span>{en ? "Download link unavailable" : "설치 링크 준비 중"}</span>}
                </li>
              ))}
            </ul>
            <p className="text-base leading-relaxed text-text-secondary">{en ? "The web demo previews face blur. Broadcast from the mobile app; check pricing for streaming time and feature limits." : "웹 체험에서 얼굴 블러 결과를 확인할 수 있습니다. 실제 방송은 모바일 앱에서 진행하며, 방송 시간과 기능 한도는 요금제에서 확인하세요."}</p>
          </section>
        )}
        {guide.comparison && (
          <section aria-labelledby="comparison-heading" className="flex min-w-0 flex-col gap-4">
            <h2 id="comparison-heading" className="text-2xl font-semibold md:text-3xl">{en ? "Compare the published features" : "공식 설명으로 기능 비교"}</h2>
            <p className="text-base leading-relaxed text-text-secondary">{guide.comparison.disclosure}</p>
            <p className="text-base text-text-secondary">{en ? "Sources checked: " : "출처 확인일: "}<time dateTime={guide.comparison.checkedAt}>{guide.comparison.checkedAt}</time></p>
            <div role="region" aria-label={guide.comparison.caption} tabIndex={0} className="overflow-x-auto rounded-card border border-surface-primary focus-visible:outline-2">
              <table className="w-full min-w-[48rem] text-left text-base leading-relaxed">
                <caption className="sr-only">{guide.comparison.caption}</caption>
                <thead><tr>{guide.comparison.columns.map((column) => <th key={column} scope="col" className="px-4 py-4">{column}</th>)}</tr></thead>
                <tbody>{guide.comparison.rows.map((row) => (
                  <tr key={row.label} className="border-t border-surface-primary align-top">
                    <th scope="row" className="px-4 py-4">{row.label}</th>
                    {row.cells.map((cell, index) => <td key={index} className="px-4 py-4">{cell}</td>)}
                  </tr>
                ))}</tbody>
              </table>
            </div>
            <h3 className="text-xl font-semibold">{en ? "Official sources" : "공식 출처"}</h3>
            <ul className="flex flex-wrap gap-x-6 gap-y-3 text-base leading-relaxed">
              {guide.comparison.sources.map((source) => <li key={source.url}><a href={source.url} className="underline underline-offset-4">{source.name}</a></li>)}
              <li><Link href={`/${locale}/mosaic-software`} className="underline underline-offset-4">{en ? "InnoLive app and installation" : "InnoLive 앱·설치 안내"}</Link></li>
              <li><Link href={`/${locale}/pricing`} className="underline underline-offset-4">{en ? "InnoLive plans" : "InnoLive 요금제"}</Link></li>
            </ul>
          </section>
        )}
        {guide.sections.map((section) => (
          <section key={section.heading} className="flex flex-col gap-4">
            <h2 className="text-2xl font-semibold leading-snug md:text-3xl">{section.heading}</h2>
            {section.paragraphs.map((paragraph) => <p key={paragraph} className="text-body leading-relaxed">{paragraph}</p>)}
          </section>
        ))}
        {guide.showPersonalPlans && (
          <section className="flex min-w-0 flex-col gap-4" aria-labelledby="plan-summary-heading">
            <h2 id="plan-summary-heading" className="text-2xl font-semibold md:text-3xl">{en ? "Current personal plan prices" : "개인 요금제의 현재 가격"}</h2>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-base leading-relaxed">
                <caption className="sr-only">{en ? "Regular and beta monthly prices for Spark, Glow, Beam and Plasma" : "Spark·Glow·Beam·Plasma의 월 정가와 베타 기간 가격"}</caption>
                <thead><tr>
                  <th scope="col" className="px-3 py-3">{en ? "Plan" : "요금제"}</th>
                  <th scope="col" className="px-3 py-3">{en ? "Regular monthly price" : "월 정가"}</th>
                  <th scope="col" className="px-3 py-3">{en ? "Current monthly price" : "현재 월 가격"}</th>
                </tr></thead>
                <tbody>{plans.map((plan) => (
                  <tr key={plan.id} className="border-t border-surface-primary">
                    <th scope="row" className="px-3 py-3">{plan.name}</th>
                    <td className="px-3 py-3">{plan.originalPrice?.amount ?? plan.price.amount}{en ? " KRW" : "원"}</td>
                    <td className="px-3 py-3">{plan.price.amount}{en ? " KRW" : "원"}{plan.ribbon ? ` (${plan.ribbon})` : ""}</td>
                  </tr>
                ))}</tbody>
              </table>
            </div>
            <p className="text-base leading-relaxed text-text-secondary">{en ? "See " : "요금제별 방송 시간·얼굴 등록 수·송출 조건은 "}<Link href={`/${locale}/pricing`} className="underline">{en ? "pricing" : "가격 안내"}</Link>{en ? " for streaming time, face registration limits and broadcast conditions." : "에서 확인하세요."}</p>
          </section>
        )}
        {guide.faq && (
          <section id="faq" className="flex flex-col gap-6">
            <h2 className="text-2xl font-semibold md:text-3xl">{en ? "Frequently asked questions" : "자주 묻는 질문"}</h2>
            {guide.faq.map(({ question, answer }, index) => (
              <div id={`faq-${index + 1}`} key={question} className="flex flex-col gap-3">
                <h3 className="text-xl font-semibold leading-snug">{question}</h3>
                <p className="text-body leading-relaxed">{answer}</p>
              </div>
            ))}
          </section>
        )}
        <nav aria-label={en ? "Related guides" : "관련 기능과 사용 방법"} className="flex flex-col gap-4 border-t border-surface-primary pt-8">
          <h2 className="text-2xl font-semibold">{en ? "Related guides" : "관련 기능과 사용 방법"}</h2>
          {guide.related.map((path) => {
            const related = findSeoGuide(path, locale);
            return related && <Link key={path} href={`/${locale}${path}`} className="text-body leading-relaxed underline underline-offset-4">{related.heading}</Link>;
          })}
        </nav>
      </article>
    </main>
  );
}
