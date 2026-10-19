import type { Locale } from "@/lib/locales";
import type { SeoGuide } from "@/lib/seo-guides";
import { siteOrigin } from "@/lib/seo";

export const guideEvidenceCopy = {
  ko: { publisher: "작성·관리", sources: "기능과 이용 조건 확인", about: "InnoLive와 운영팀", support: "사용 방법과 고객지원", pricing: "요금제와 이용 한도", note: "제품 기능과 이용 조건은 아래 공식 안내를 기준으로 확인하세요. 감지 성능은 촬영 환경에 따라 달라질 수 있습니다." },
  en: { publisher: "Published and maintained by", sources: "Features and usage conditions", about: "InnoLive and the team", support: "Usage and customer support", pricing: "Plans and usage limits", note: "Check the official guides below for features and usage conditions. Detection results can vary with the filming environment." },
  ja: { publisher: "作成・管理", sources: "機能と利用条件の確認", about: "InnoLiveと運営チーム", support: "使い方とサポート", pricing: "料金プランと利用上限", note: "機能と利用条件は以下の公式ガイドで確認してください。検出結果は撮影環境によって異なる場合があります。" },
} as const;

// Dates describe verified source checks, not the current build time.
export function guideEvidence(guide: SeoGuide, locale: Locale) {
  const copy = guideEvidenceCopy[locale];
  return {
    question: guide.faq?.[0]?.question ?? guide.heading,
    answer: guide.intro,
    dataAsOf: guide.comparison?.checkedAt ?? null,
    publisher: "Framework",
    sources: [
      { name: copy.about, url: `${siteOrigin}/${locale}/about` },
      { name: copy.support, url: `${siteOrigin}/${locale}/support` },
      { name: copy.pricing, url: `${siteOrigin}/${locale}/pricing` },
      ...(guide.comparison?.sources ?? []),
    ],
  };
}
