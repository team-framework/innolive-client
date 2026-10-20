import { guideEvidence, guideEvidenceCopy } from "@/lib/guide-evidence";
import type { Locale } from "@/lib/locales";
import Image from "next/image";
import Link from "next/link";
import { Button } from "@/components/button";
import { findSeoGuide, type SeoGuide } from "@/lib/seo-guides";
import { StructuredData } from "@/components/structured-data";
import { guideStructuredData } from "@/lib/structured-data";
import { getMessages } from "@/lib/messages";
import { downloadPlatforms } from "@/lib/site";
import { getPersonalPlans } from "@/lib/plans";

export function SeoGuidePage({ guide, locale = "ko" }: { guide: SeoGuide; locale?: Locale }) {
  const copy = getMessages(locale).seoGuide;
  const evidence = guideEvidence(guide, locale);
  const evidenceCopy = guideEvidenceCopy[locale];
  const plans = guide.showPersonalPlans ? getPersonalPlans(getMessages(locale)) : [];
  return (
    <main id="main" tabIndex={-1} className="page-top-spacing min-h-dvh bg-background-primary px-[var(--page-gutter)] pb-20">
      <StructuredData data={guideStructuredData(guide, locale)} />
      <article className="mx-auto flex max-w-[72rem] min-w-0 flex-col gap-12 break-keep">
        <nav aria-label={copy.breadcrumb} className="flex flex-wrap gap-3 max-lg:items-center text-base leading-relaxed text-text-secondary">
          <Link href={`/${locale}`} className="underline max-lg:inline-flex max-lg:min-h-[44px] max-lg:items-center">InnoLive</Link>
          <span aria-hidden="true">/</span>
          <span aria-current="page">{guide.heading}</span>
        </nav>
        <div className="flex flex-col gap-6">
          <h1 className="text-heading font-bold leading-snug">{guide.heading}</h1>
          <p className="max-w-[60rem] text-body leading-relaxed">{guide.intro}</p>
          <div className="flex flex-wrap gap-3">
            <Button href={`/${locale}/try-out`} variant="secondary" showChevron={false}>{copy.tryBlur}</Button>
            <Button href={`/${locale}/pricing`} showChevron={false}>{copy.viewPlans}</Button>
          </div>
        </div>
        {["/live-mosaic", "/face-mosaic", "/mobile-live-face-blur"].includes(guide.path) && (
          <figure className="flex flex-col gap-4">
            <Image src="/mockups/hero-face-registration.webp" alt={copy.registrationAlt} width={640} height={960} className="h-auto max-h-[32rem] w-full rounded-card bg-background-secondary object-contain" />
            <figcaption className="text-base leading-relaxed text-text-secondary">{copy.registrationCaption}</figcaption>
          </figure>
        )}
        {guide.path === "/mosaic-software" && (
          <section id="install" aria-labelledby="install-heading" className="flex flex-col gap-4 rounded-card bg-background-secondary p-6">
            <h2 id="install-heading" className="text-2xl font-semibold md:text-3xl">{copy.installTitle}</h2>
            <p className="text-body leading-relaxed">{copy.installDescription}</p>
            <ul className="flex flex-col gap-3 text-body leading-relaxed">
              {downloadPlatforms.map((platform) => (
                <li key={platform.id}>
                  <span>{platform.name} · {platform.minOs} · </span>
                  {platform.href ? <a href={platform.href} className="underline underline-offset-4">{platform.id === "ios" ? "App Store" : "ONE store"}{copy.downloadSuffix}</a> : <span>{copy.downloadUnavailable}</span>}
                </li>
              ))}
            </ul>
            <p className="text-base leading-relaxed text-text-secondary">{copy.demoDescription}</p>
          </section>
        )}
        {guide.comparison && (
          <section aria-labelledby="comparison-heading" className="flex min-w-0 flex-col gap-4">
            <h2 id="comparison-heading" className="text-2xl font-semibold md:text-3xl">{copy.comparisonTitle}</h2>
            <p className="text-base leading-relaxed text-text-secondary">{guide.comparison.disclosure}</p>
            <p className="text-base text-text-secondary">{copy.sourcesChecked}<time dateTime={guide.comparison.checkedAt}>{guide.comparison.checkedAt}</time></p>
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
            <h3 className="text-xl font-semibold">{copy.officialSources}</h3>
            <ul className="flex flex-wrap gap-x-6 gap-y-3 text-base leading-relaxed">
              {guide.comparison.sources.map((source) => <li key={source.url}><a href={source.url} className="underline underline-offset-4">{source.name}</a></li>)}
              <li><Link href={`/${locale}/mosaic-software`} className="underline underline-offset-4">{copy.appInstallation}</Link></li>
              <li><Link href={`/${locale}/pricing`} className="underline underline-offset-4">{copy.plans}</Link></li>
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
            <h2 id="plan-summary-heading" className="text-2xl font-semibold md:text-3xl">{copy.planSummary}</h2>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-base leading-relaxed">
                <caption className="sr-only">{copy.planCaption}</caption>
                <thead><tr>
                  <th scope="col" className="px-3 py-3">{copy.plan}</th>
                  <th scope="col" className="px-3 py-3">{copy.regularPrice}</th>
                  <th scope="col" className="px-3 py-3">{copy.currentPrice}</th>
                </tr></thead>
                <tbody>{plans.map((plan) => (
                  <tr key={plan.id} className="border-t border-surface-primary">
                    <th scope="row" className="px-3 py-3">{plan.name}</th>
                    <td className="px-3 py-3">{plan.originalPrice?.amount ?? plan.price.amount}{copy.currencySuffix}</td>
                    <td className="px-3 py-3">{plan.price.amount}{copy.currencySuffix}{plan.ribbon ? ` (${plan.ribbon})` : ""}</td>
                  </tr>
                ))}</tbody>
              </table>
            </div>
            <p className="text-base leading-relaxed text-text-secondary">{copy.pricingBefore}<Link href={`/${locale}/pricing`} className="underline">{copy.pricingLink}</Link>{copy.pricingAfter}</p>
          </section>
        )}
        {guide.faq && (
          <section id="faq" className="flex flex-col gap-6">
            <h2 className="text-2xl font-semibold md:text-3xl">{copy.faq}</h2>
            {guide.faq.map(({ question, answer }, index) => (
              <div id={`faq-${index + 1}`} key={question} className="flex flex-col gap-3">
                <h3 className="text-xl font-semibold leading-snug">{question}</h3>
                <p className="text-body leading-relaxed">{answer}</p>
              </div>
            ))}
          </section>
        )}
        <section aria-labelledby="guide-sources-heading" className="flex flex-col gap-4 border-t border-surface-primary pt-8">
          <h2 id="guide-sources-heading" className="text-2xl font-semibold">{evidenceCopy.sources}</h2>
          <p>{evidenceCopy.publisher}: <Link href={`/${locale}/about`} className="underline underline-offset-4">{evidence.publisher}</Link></p>
          <p className="text-base leading-relaxed text-text-secondary">{evidenceCopy.note}</p>
          <ul className="flex flex-wrap gap-x-6 gap-y-2">
            {evidence.sources.slice(0, 3).map(({ name, url }) => <li key={url}><Link href={url} className="inline-flex min-h-[44px] items-center underline underline-offset-4">{name}</Link></li>)}
          </ul>
        </section>
        <nav aria-label={copy.related} className="flex flex-col gap-4 border-t border-surface-primary pt-8 max-lg:gap-1">
          <h2 className="text-2xl font-semibold">{copy.related}</h2>
          {guide.related.map((path) => {
            const related = findSeoGuide(path, locale);
            return related && <Link key={path} href={`/${locale}${path}`} className="text-body leading-relaxed underline underline-offset-4 max-lg:inline-flex max-lg:min-h-[44px] max-lg:items-center">{related.heading}</Link>;
          })}
        </nav>
      </article>
    </main>
  );
}
