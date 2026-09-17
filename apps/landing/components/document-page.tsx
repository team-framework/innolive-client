import type { MDXComponents } from "mdx/types";
import Link from "next/link";
import type { ComponentType } from "react";
import { documentMdxComponents } from "@/components/document-mdx";
import {
  documentLocales,
  documentNavLabel,
  documentPath,
  documentSlugs,
  documentTitles,
  languageNames,
  languageNavLabel,
  type DocumentLocale,
  type DocumentSlug,
} from "@/lib/documents";

type DocumentContent = ComponentType<{ components?: MDXComponents }>;

function DocumentChrome({
  locale,
  slug,
}: {
  locale: DocumentLocale;
  slug: DocumentSlug;
}) {
  return (
    <div className="mb-10 flex w-full max-w-[72rem] flex-col gap-4 text-base leading-[1.3] text-text-secondary">
      <nav
        aria-label={languageNavLabel[locale]}
        className="flex flex-wrap gap-x-3 gap-y-2"
      >
        {documentLocales.map((item) => {
          const current = item === locale;
          return (
            <Link
              key={item}
              href={documentPath(slug, item)}
              hrefLang={item}
              lang={item}
              aria-current={current ? "page" : undefined}
              className={
                current
                  ? "font-semibold text-text-primary underline"
                  : "underline"
              }
            >
              {languageNames[item]}
            </Link>
          );
        })}
      </nav>
      <nav
        aria-label={documentNavLabel[locale]}
        className="flex flex-wrap gap-x-3 gap-y-2"
      >
        {documentSlugs.map((item) => {
          const current = item === slug;
          return (
            <Link
              key={item}
              href={documentPath(item, locale)}
              aria-current={current ? "page" : undefined}
              className={
                current
                  ? "font-semibold text-text-primary underline"
                  : "underline"
              }
            >
              {documentTitles[item][locale]}
            </Link>
          );
        })}
      </nav>
    </div>
  );
}

export function DocumentPage({
  locale,
  slug,
  Content,
}: {
  locale: DocumentLocale;
  slug: DocumentSlug;
  Content: DocumentContent;
}) {
  return (
    <main
      id="main"
      data-page="policy"
      lang={locale}
      className="min-h-dvh overflow-x-clip bg-background-primary"
    >
      <section
        className="flex w-full flex-col items-center overflow-x-clip px-[var(--page-gutter)] pb-16 pt-16 lg:pt-24 min-[106.5rem]:pb-[5.25rem] min-[106.5rem]:pt-[9.5rem]"
        aria-labelledby="document-heading"
      >
        <div className="flex w-full max-w-[100rem] min-w-0 flex-col text-text-primary">
          <DocumentChrome locale={locale} slug={slug} />
          <article className="w-full min-w-0 max-w-[72rem]" lang={locale}>
            <Content components={documentMdxComponents(locale)} />
          </article>
        </div>
      </section>
    </main>
  );
}
