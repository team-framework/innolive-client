import type { Metadata } from 'next'
import { notFound } from 'next/navigation'

import { I18nProvider } from '../../components/I18nProvider'
import { getMessages, getTranslation } from '../../lib/i18n'
import '../globals.css'

type LocaleLayoutProps = Readonly<{
  children: React.ReactNode
  params: Promise<{ locale: string }>
}>

export async function generateMetadata({ params }: LocaleLayoutProps): Promise<Metadata> {
  const { locale } = await params
  const t = await getTranslation(locale)
  return { title: 'InnoLive', description: t('metadata.description') }
}

export default async function LocaleLayout({ children, params }: LocaleLayoutProps) {
  const { locale } = await params

  if (!['ko', 'en', 'ja'].includes(locale)) notFound()

  return (
    <html lang={locale}>
      <body>
        <I18nProvider key={locale} locale={locale} messages={getMessages(locale)} fallbackMessages={getMessages('ko')}>{children}</I18nProvider>
      </body>
    </html>
  )
}
