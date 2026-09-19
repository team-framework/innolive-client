import type { ComponentType } from "react";
import type { MDXComponents } from "mdx/types";
import PrivacyEn from "@/content/documents/privacy-policy.en.mdx";
import PrivacyJa from "@/content/documents/privacy-policy.ja.mdx";
import PrivacyKo from "@/content/documents/privacy-policy.ko.mdx";
import SupportEn from "@/content/documents/support.en.mdx";
import SupportJa from "@/content/documents/support.ja.mdx";
import SupportKo from "@/content/documents/support.ko.mdx";
import TermsEn from "@/content/documents/terms-of-service.en.mdx";
import TermsJa from "@/content/documents/terms-of-service.ja.mdx";
import TermsKo from "@/content/documents/terms-of-service.ko.mdx";
import type { Locale } from "@/lib/locales";
import type { DocumentSlug } from "@/lib/documents";

type DocumentContent = ComponentType<{ components?: MDXComponents }>;

export const documentContent: Record<
  DocumentSlug,
  Record<Locale, DocumentContent>
> = {
  privacy: { ko: PrivacyKo, en: PrivacyEn, ja: PrivacyJa },
  terms: { ko: TermsKo, en: TermsEn, ja: TermsJa },
  support: { ko: SupportKo, en: SupportEn, ja: SupportJa },
};
