import type { MDXComponents } from "mdx/types";
import { documentMdxComponents } from "@/components/document-mdx";

export function useMDXComponents(mdxComponents: MDXComponents): MDXComponents {
  return {
    ...mdxComponents,
    ...documentMdxComponents("ko"),
  };
}
