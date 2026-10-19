import Link from "next/link";
import { notFound } from "next/navigation";
import { StructuredData } from "@/components/structured-data";
import { aboutCopy } from "@/lib/about";
import { isLocale } from "@/lib/locales";
import { pageMetadata, siteOrigin } from "@/lib/seo";
import { socialLinks } from "@/lib/site";
import { aboutStructuredData } from "@/lib/structured-data";

type Props = { params: Promise<{ locale: string }> };

export async function generateMetadata({ params }: Props) {
  const { locale } = await params;
  if (!isLocale(locale)) return {};
  return pageMetadata(locale, "/about", aboutCopy[locale]);
}

export default async function AboutPage({ params }: Props) {
  const { locale } = await params;
  if (!isLocale(locale)) notFound();
  const copy = aboutCopy[locale];
  const facts = [[copy.name, "InnoLive (이노라이브)"], [copy.team, "Framework"],
    [copy.website, siteOrigin], [copy.platforms, "Android · iOS"], [copy.trial, copy.trialValue]];
  return (
    <main id="main" tabIndex={-1} className="page-top-spacing bg-background-primary px-[var(--page-gutter)] pb-20">
      <StructuredData data={aboutStructuredData(locale)} />
      <article className="mx-auto flex max-w-[72rem] flex-col gap-8 break-keep">
        <h1 className="text-balance text-3xl font-bold leading-snug lg:text-5xl">{copy.heading}</h1>
        <p className="max-w-[60rem] text-body leading-relaxed">{copy.intro}</p>
        <section className="flex flex-col gap-4">
          <h2 className="text-2xl font-semibold">{copy.factsTitle}</h2>
          <dl className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            {facts.map(([label, value]) => <div key={label}><dt className="font-semibold">{label}</dt><dd className="mt-1 text-text-secondary">{value}</dd></div>)}
          </dl>
        </section>
        <section className="flex flex-col gap-4">
          <h2 className="text-2xl font-semibold">{copy.limitsTitle}</h2>
          <p className="text-body leading-relaxed">{copy.limits}</p>
        </section>
        <section className="flex flex-col gap-4">
          <h2 className="text-2xl font-semibold">{copy.sourcesTitle}</h2>
          <p className="text-body leading-relaxed">{copy.sourceNote}</p>
          <ul className="flex flex-col gap-2">
            {[["/live-mosaic", copy.guide], ["/pricing", copy.pricing], ["/support", copy.support]].map(([path, label]) => <li key={path}><Link className="inline-flex min-h-[44px] items-center underline" href={`/${locale}${path}`}>{label}</Link></li>)}
            {socialLinks.map(({ name, href }) => <li key={href}><a className="inline-flex min-h-[44px] items-center underline" href={href}>Framework · {name}</a></li>)}
          </ul>
        </section>
      </article>
    </main>
  );
}
