import type { ReactNode } from "react";
import type { DocumentLocale } from "@/lib/documents";

export function DocumentPage({
  children,
  locale = "ko",
}: {
  children: ReactNode;
  locale?: DocumentLocale;
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
        <article className="w-full min-w-0 max-w-[100rem] text-text-primary">
          {children}
        </article>
      </section>
    </main>
  );
}
