import type { MDXComponents } from "mdx/types";
import Link from "next/link";
import type { ComponentPropsWithoutRef } from "react";

const documentRoutes: Record<string, string> = {
  "privacy-policy.ko.md": "/privacy",
  "privacy-policy.ko.mdx": "/privacy",
  "terms-of-service.ko.md": "/terms",
  "terms-of-service.ko.mdx": "/terms",
  "support.ko.md": "/support",
  "support.ko.mdx": "/support",
};

function hrefPath(href: string): string {
  let path = href;
  const queryAt = path.indexOf("?");
  if (queryAt >= 0) {
    path = path.slice(0, queryAt);
  }
  const hashAt = path.indexOf("#");
  if (hashAt >= 0) {
    path = path.slice(0, hashAt);
  }
  return path;
}

function hrefSuffix(href: string): string {
  const path = hrefPath(href);
  return href.slice(path.length);
}

function fileName(href: string): string {
  const path = hrefPath(href);
  const slashAt = path.lastIndexOf("/");
  return slashAt >= 0 ? path.slice(slashAt + 1) : path;
}

function resolveDocumentHref(href: string): string {
  const route = documentRoutes[fileName(href)];
  if (!route) {
    return href;
  }
  return `${route}${hrefSuffix(href)}`;
}

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

const components = {
  h1: ({ children, id, ...props }: ComponentPropsWithoutRef<"h1">) => (
    <h1
      id={id ?? "document-heading"}
      className="mb-8 break-keep text-[clamp(2rem,1.2rem+3.2vw,4rem)] font-bold leading-none"
      {...props}
    >
      {children}
    </h1>
  ),
  h2: ({ children, ...props }: ComponentPropsWithoutRef<"h2">) => (
    <h2
      className="mt-10 mb-4 break-keep text-[clamp(1.25rem,1.05rem+1vw,1.75rem)] font-semibold leading-[1.3]"
      {...props}
    >
      {children}
    </h2>
  ),
  h3: ({ children, ...props }: ComponentPropsWithoutRef<"h3">) => (
    <h3
      className="mt-8 mb-3 break-keep text-[clamp(1.0625rem,1rem+0.4vw,1.25rem)] font-semibold leading-[1.3]"
      {...props}
    >
      {children}
    </h3>
  ),
  p: ({ children, ...props }: ComponentPropsWithoutRef<"p">) => (
    <p
      className="my-3 break-keep text-body-lg font-normal leading-[1.25]"
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
    <li className="break-keep" {...props}>
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
      aria-label="표"
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
      className="break-keep border border-surface-primary px-3 py-2 align-top font-semibold"
      {...props}
    >
      {children}
    </th>
  ),
  td: ({ children, ...props }: ComponentPropsWithoutRef<"td">) => (
    <td
      className="break-keep border border-surface-primary px-3 py-2 align-top"
      {...props}
    >
      {children}
    </td>
  ),
  pre: ({ children, ...props }: ComponentPropsWithoutRef<"pre">) => (
    <pre
      role="region"
      aria-label="미리 서식된 블록"
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
} satisfies MDXComponents;

export function useMDXComponents(mdxComponents: MDXComponents): MDXComponents {
  return {
    ...mdxComponents,
    ...components,
  };
}
