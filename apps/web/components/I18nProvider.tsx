'use client'

import { createInstance, type ResourceLanguage } from 'i18next'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import { useState } from 'react'

type I18nProviderProps = {
  children: React.ReactNode
  fallbackMessages: ResourceLanguage
  locale: string
  messages: ResourceLanguage
}

export function I18nProvider({ children, fallbackMessages, locale, messages }: I18nProviderProps) {
  const [i18n] = useState(() => {
    const instance = createInstance()
    void instance.use(initReactI18next).init({
      fallbackLng: 'ko',
      initAsync: false,
      interpolation: { escapeValue: false },
      lng: locale,
      resources: {
        [locale]: { translation: messages },
        ko: { translation: fallbackMessages },
      },
    })
    return instance
  })

  return <I18nextProvider i18n={i18n}>{children}</I18nextProvider>
}
