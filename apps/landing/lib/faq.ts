import { localePath, type Locale } from "@/lib/locales";
import { getMessages } from "@/lib/messages";

export type FaqItem = {
  question: string;
  answer: string;
  answerLink?: { before: string; label: string; after: string; href: string };
};

export function getHomeFaq(locale: Locale): FaqItem[] {
  const { faq } = getMessages(locale);
  return [
    ...faq.items,
    ...(locale === "ko" ? [{
      question: "Q. 실시간 방송에서 행인 얼굴을 가릴 수 있나요?",
      answer: "공개 대상자로 등록하지 않은 행인의 얼굴은 실시간 블러로 자동으로 가려집니다. 얼굴 등록부터 YouTube·CHZZK 송출 준비까지는 실시간 방송 모자이크 안내에서 확인할 수 있습니다.",
      answerLink: {
        before: "공개 대상자로 등록하지 않은 행인의 얼굴은 실시간 블러로 자동으로 가려집니다. 얼굴 등록부터 YouTube·CHZZK 송출 준비까지는 ",
        label: "실시간 방송 모자이크 안내",
        after: "에서 확인할 수 있습니다.",
        href: localePath(locale, "/live-mosaic"),
      },
    }] : []),
    {
      question: faq.privacyQuestion,
      answer: `${faq.privacyBefore}${faq.privacyLink}${faq.privacyAfter}`,
      answerLink: {
        before: faq.privacyBefore,
        label: faq.privacyLink,
        after: faq.privacyAfter,
        href: localePath(locale, "/privacy"),
      },
    },
  ];
}
