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
      question: "실시간 방송에서 행인 얼굴을 어떻게 가리나요?",
      answer: "공개할 얼굴을 등록하고 미등록 얼굴을 실시간 블러로 가리세요. 실시간 방송 모자이크 안내에서 얼굴 등록부터 유튜브·치지직(CHZZK) 송출 준비까지 확인할 수 있습니다.",
      answerLink: {
        before: "공개할 얼굴을 등록하고 미등록 얼굴을 실시간 블러로 가리세요. ",
        label: "실시간 방송 모자이크 안내",
        after: "에서 얼굴 등록부터 유튜브·치지직(CHZZK) 송출 준비까지 확인할 수 있습니다.",
        href: "/ko/live-mosaic",
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
