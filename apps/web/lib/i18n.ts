import { createInstance, type ResourceLanguage } from 'i18next'

import en from '../public/locales/en/translation.json'
import ja from '../public/locales/ja/translation.json'
import ko from '../public/locales/ko/translation.json'

const resources: Record<string, ResourceLanguage> = { en, ja, ko }

export function getMessages(locale: string) {
  return resources[locale] ?? ko
}

export async function getTranslation(locale: string) {
  const i18n = createInstance()
  await i18n.init({
    fallbackLng: 'ko',
    lng: locale,
    resources: {
      [locale]: { translation: getMessages(locale) },
      ko: { translation: ko },
    },
  })
  return i18n.getFixedT(locale)
}
