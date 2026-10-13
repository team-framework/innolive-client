import type { MDXComponents } from "mdx/types";
import type { ComponentType } from "react";
import { documentMdxComponents } from "@/components/document-mdx";
import type { DocumentLocale } from "@/lib/documents";

type DocumentContent = ComponentType<{ components?: MDXComponents }>;

export function DocumentPage({
  locale,
  Content,
}: {
  locale: DocumentLocale;
  Content: DocumentContent;
}) {
  return (
    <main
      id="main"
      tabIndex={-1}
      data-page="policy"
      lang={locale}
      className="min-h-dvh overflow-x-clip bg-background-primary"
    >
      <section
        className="page-top-spacing flex w-full flex-col items-center overflow-x-clip px-[var(--page-gutter)] pb-16 min-[106.5rem]:pb-[5.25rem]"
        aria-labelledby="document-heading"
      >
        <div className="flex w-full max-w-[100rem] min-w-0 flex-col text-text-primary">
          <article className="w-full min-w-0 max-w-[72rem]" lang={locale}>
            <Content components={documentMdxComponents(locale)} />
          </article>
        </div>
      </section>
    </main>
  );
}
