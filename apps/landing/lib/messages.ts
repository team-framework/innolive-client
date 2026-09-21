import en from "@/messages/en.json";
import ja from "@/messages/ja.json";
import ko from "@/messages/ko.json";
import { defaultLocale, isLocale } from "@/lib/locales";

const dictionaries = {
  ko,
  en,
  ja,
} as const;

export type Messages = typeof ko;

export function getMessages(locale: string): Messages {
  if (isLocale(locale)) {
    return dictionaries[locale] as Messages;
  }
  return dictionaries[defaultLocale];
}
