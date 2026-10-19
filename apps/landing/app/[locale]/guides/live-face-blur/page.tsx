import Link from "next/link";
import { notFound } from "next/navigation";
import { isLocale, localePath } from "@/lib/locales";
import { liveFaceBlurGuide } from "@/lib/live-face-blur-guide";
import { pageMetadata } from "@/lib/seo";

type PageProps = { params: Promise<{ locale: string }> };

export async function generateMetadata({ params }: PageProps) {
  const { locale } = await params;
  if (!isLocale(locale)) return {};
  return pageMetadata(locale, "/guides/live-face-blur", liveFaceBlurGuide[locale]);
}

export default async function LiveFaceBlurGuide({ params }: PageProps) {
  const { locale } = await params;
  if (!isLocale(locale)) notFound();
  const guide = liveFaceBlurGuide[locale];
  return (
    <main id="main" tabIndex={-1} className="page-top-spacing bg-background-primary px-[var(--page-gutter)] pb-16 lg:pb-24">
      <article className="mx-auto max-w-[72rem] break-keep text-text-primary">
        <h1 className="text-balance text-3xl font-bold leading-snug lg:text-5xl">{guide.heading}</h1>
        <p className="mt-8 text-lg leading-relaxed max-md:text-[15px]">{guide.intro}</p>
        {guide.sections.map((section) => (
          <section key={section.heading} className="mt-12">
            <h2 className="text-balance text-2xl font-semibold leading-snug">{section.heading}</h2>
            {section.paragraphs.map((paragraph) => <p key={paragraph} className="mt-4 text-lg leading-relaxed max-md:text-[15px]">{paragraph}</p>)}
            {section.steps && <ol className="mt-4 list-decimal space-y-3 pl-6 text-lg leading-relaxed max-md:text-[15px]">{section.steps.map((step) => <li key={step}>{step}</li>)}</ol>}
          </section>
        ))}
        <nav className="mt-12 flex flex-wrap gap-6 text-lg max-md:text-[15px] max-lg:gap-x-6 max-lg:gap-y-0">
          {(locale === "ko" || locale === "en") && <Link href={localePath(locale, "/live-mosaic")} className="underline max-lg:inline-flex max-lg:min-h-[44px] max-lg:items-center">{locale === "en" ? "Live face blur guide" : "실시간 얼굴 가리기 가이드"}</Link>}
          <Link href={localePath(locale, "/try-out")} className="underline max-lg:inline-flex max-lg:min-h-[44px] max-lg:items-center">{guide.demo}</Link>
          <Link href={localePath(locale, "/support")} className="underline max-lg:inline-flex max-lg:min-h-[44px] max-lg:items-center">{guide.support}</Link>
        </nav>
      </article>
    </main>
  );
}
