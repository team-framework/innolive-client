import type { Metadata } from "next";
import { isLocale, localePath, locales, type Locale } from "@/lib/locales";

export type DocumentLocale = Locale;
export type DocumentSlug = "privacy" | "terms" | "support";

export const documentLocales = locales;
export const documentSlugs = ["privacy", "terms", "support"] as const;

export const documentTitles = {
  privacy: {
    ko: "개인정보 처리방침",
    en: "Privacy Policy",
    ja: "個人情報取扱方針",
  },
  terms: {
    ko: "이용약관",
    en: "Terms of Service",
    ja: "サービス利用規約",
  },
  support: {
    ko: "고객 지원",
    en: "Customer Support",
    ja: "カスタマーサポート",
  },
} as const;

export const documentDescriptions = {
  privacy: {
    ko: "개인정보의 처리 항목, 목적과 보관·삭제 기준을 안내합니다.",
    en: "This document explains the personal information we process, the purposes of processing, and the criteria for retention and deletion.",
    ja: "個人情報の取扱い項目、目的、保管・削除の基準をご案内します。",
  },
  terms: {
    ko: "서비스 이용 조건과 이용자·운영팀의 권리 및 의무를 안내합니다.",
    en: "This document explains the conditions of use and the rights and obligations of users and the Operations Team.",
    ja: "サービスの利用条件と、利用者・運営チームの権利および義務をご案内します。",
  },
  support: {
    ko: "서비스 이용, 오류, 계정 및 개인정보 관련 문의를 접수합니다.",
    en: "We accept inquiries about using the service, errors, accounts, and personal information.",
    ja: "サービスの利用、エラー、アカウントおよび個人情報に関するお問い合わせを受け付けます。",
  },
} as const;

export const languageNames: Record<DocumentLocale, string> = {
  ko: "한국어",
  en: "English",
  ja: "日本語",
};

export const languageNavLabel: Record<DocumentLocale, string> = {
  ko: "문서 언어",
  en: "Document language",
  ja: "文書の言語",
};

export const documentNavLabel: Record<DocumentLocale, string> = {
  ko: "문서",
  en: "Documents",
  ja: "文書",
};

export const tableRegionLabel: Record<DocumentLocale, string> = {
  ko: "표",
  en: "Table",
  ja: "表",
};

export const preRegionLabel: Record<DocumentLocale, string> = {
  ko: "미리 서식된 블록",
  en: "Preformatted block",
  ja: "整形済みブロック",
};

const documentFileStems: Record<string, DocumentSlug> = {
  "privacy-policy": "privacy",
  "terms-of-service": "terms",
  support: "support",
};

export function documentPath(
  slug: DocumentSlug,
  locale: DocumentLocale,
): string {
  const leaf =
    slug === "privacy" ? "privacy" : slug === "terms" ? "terms" : "support";
  return localePath(locale, `/${leaf}`);
}

export function documentMetadata(
  slug: DocumentSlug,
  locale: DocumentLocale,
): Metadata {
  return {
    title: documentTitles[slug][locale],
    description: documentDescriptions[slug][locale],
  };
}

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
  return href.slice(hrefPath(href).length);
}

function fileName(href: string): string {
  const path = hrefPath(href);
  const slashAt = path.lastIndexOf("/");
  return slashAt >= 0 ? path.slice(slashAt + 1) : path;
}

function isDocumentLocale(value: string): value is DocumentLocale {
  return isLocale(value);
}

function isRelativeDocumentHref(href: string): boolean {
  if (href.startsWith("/") || href.startsWith("//")) {
    return false;
  }

  const colon = href.indexOf(":");
  if (colon >= 0) {
    const slash = href.indexOf("/");
    if (slash < 0 || colon < slash) {
      return false;
    }
  }

  return true;
}

export function resolveDocumentHref(href: string): string {
  if (!isRelativeDocumentHref(href)) {
    return href;
  }

  const name = fileName(href);
  let stemWithLocale = name;
  if (stemWithLocale.endsWith(".mdx")) {
    stemWithLocale = stemWithLocale.slice(0, -4);
  } else if (stemWithLocale.endsWith(".md")) {
    stemWithLocale = stemWithLocale.slice(0, -3);
  } else {
    return href;
  }

  const dot = stemWithLocale.lastIndexOf(".");
  if (dot < 0) {
    return href;
  }

  const stem = stemWithLocale.slice(0, dot);
  const locale = stemWithLocale.slice(dot + 1);
  if (!isDocumentLocale(locale)) {
    return href;
  }

  const slug = documentFileStems[stem];
  if (!slug) {
    return href;
  }

  return `${documentPath(slug, locale)}${hrefSuffix(href)}`;
}
