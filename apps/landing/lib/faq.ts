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
