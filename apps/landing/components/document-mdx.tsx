import type { MDXComponents } from "mdx/types";
import Link from "next/link";
import type { ComponentPropsWithoutRef } from "react";
import {
  preRegionLabel,
  resolveDocumentHref,
  tableRegionLabel,
  type DocumentLocale,
} from "@/lib/documents";

function isAppPath(href: string): boolean {
  return href.startsWith("/") && !href.startsWith("//");
}

const scrollRegionClassName =
  "my-4 max-w-full overflow-x-auto overscroll-x-contain";

function MdxAnchor({
  href,
  children,
  ...props
}: ComponentPropsWithoutRef<"a">) {
  const resolved = href ? resolveDocumentHref(href) : href;

  if (resolved && isAppPath(resolved)) {
    return (
      <Link
        href={resolved}
        className="break-words underline underline-offset-4"
        {...props}
      >
        {children}
      </Link>
    );
  }

  return (
    <a
      href={resolved}
      className="break-words underline underline-offset-4"
      {...props}
    >
      {children}
    </a>
  );
}

export function documentMdxComponents(locale: DocumentLocale): MDXComponents {
  const wrapClassName =
    locale === "ja" ? "break-normal break-words" : "break-keep";

  return {
    h1: ({ children, id, ...props }: ComponentPropsWithoutRef<"h1">) => (
      <h1
        id={id ?? "document-heading"}
        className={`mb-8 ${wrapClassName} text-[clamp(2rem,1.2rem+3.2vw,4rem)] font-bold leading-none`}
        {...props}
      >
        {children}
      </h1>
    ),
    h2: ({ children, ...props }: ComponentPropsWithoutRef<"h2">) => (
      <h2
        className={`mt-10 mb-4 ${wrapClassName} text-[clamp(1.25rem,1.05rem+1vw,1.75rem)] font-semibold leading-[1.3]`}
        {...props}
      >
        {children}
      </h2>
    ),
    h3: ({ children, ...props }: ComponentPropsWithoutRef<"h3">) => (
      <h3
        className={`mt-8 mb-3 ${wrapClassName} text-[clamp(1.0625rem,1rem+0.4vw,1.25rem)] font-semibold leading-[1.3]`}
        {...props}
      >
        {children}
      </h3>
    ),
    p: ({ children, ...props }: ComponentPropsWithoutRef<"p">) => (
      <p
        className={`my-3 ${wrapClassName} text-body-lg font-normal leading-[1.25]`}
        {...props}
      >
        {children}
      </p>
    ),
    ul: ({ children, ...props }: ComponentPropsWithoutRef<"ul">) => (
      <ul
        className="my-3 list-disc space-y-2 pl-6 text-body-lg font-normal leading-[1.25]"
        {...props}
      >
        {children}
      </ul>
    ),
    ol: ({ children, ...props }: ComponentPropsWithoutRef<"ol">) => (
      <ol
        className="my-3 list-decimal space-y-2 pl-6 text-body-lg font-normal leading-[1.25]"
        {...props}
      >
        {children}
      </ol>
    ),
    li: ({ children, ...props }: ComponentPropsWithoutRef<"li">) => (
      <li className={wrapClassName} {...props}>
        {children}
      </li>
    ),
    a: MdxAnchor,
    strong: ({ children, ...props }: ComponentPropsWithoutRef<"strong">) => (
      <strong className="font-semibold" {...props}>
        {children}
      </strong>
    ),
    table: ({ children, ...props }: ComponentPropsWithoutRef<"table">) => (
      <div
        role="region"
        aria-label={tableRegionLabel[locale]}
        tabIndex={0}
        className={scrollRegionClassName}
      >
        <table
          className="w-full border-collapse text-left text-[clamp(0.875rem,0.8rem+0.3vw,1rem)] leading-[1.4]"
          {...props}
        >
          {children}
        </table>
      </div>
    ),
    thead: ({ children, ...props }: ComponentPropsWithoutRef<"thead">) => (
      <thead className="bg-surface-primary" {...props}>
        {children}
      </thead>
    ),
    th: ({ children, ...props }: ComponentPropsWithoutRef<"th">) => (
      <th
        className={`${wrapClassName} border border-surface-primary px-3 py-2 align-top font-semibold`}
        {...props}
      >
        {children}
      </th>
    ),
    td: ({ children, ...props }: ComponentPropsWithoutRef<"td">) => (
      <td
        className={`${wrapClassName} border border-surface-primary px-3 py-2 align-top`}
        {...props}
      >
        {children}
      </td>
    ),
    pre: ({ children, ...props }: ComponentPropsWithoutRef<"pre">) => (
      <pre
        role="region"
        aria-label={preRegionLabel[locale]}
        tabIndex={0}
        className={`${scrollRegionClassName} whitespace-pre rounded-[var(--radius-control)] bg-surface-primary p-4 text-[0.875rem] leading-[1.45] [&_code]:bg-transparent [&_code]:p-0`}
        {...props}
      >
        {children}
      </pre>
    ),
    code: ({ children, ...props }: ComponentPropsWithoutRef<"code">) => (
      <code
        className="break-all rounded-[var(--radius-control)] bg-surface-primary px-1 py-0.5 font-mono text-[0.875rem]"
        {...props}
      >
        {children}
      </code>
    ),
    hr: (props: ComponentPropsWithoutRef<"hr">) => (
      <hr className="my-8 border-surface-primary" {...props} />
    ),
    blockquote: ({
      children,
      ...props
    }: ComponentPropsWithoutRef<"blockquote">) => (
      <blockquote
        className="my-4 border-l-2 border-text-primary pl-4 text-body-lg leading-[1.25]"
        {...props}
      >
        {children}
      </blockquote>
    ),
  };
}
