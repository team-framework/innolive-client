import Link from 'next/link'

import { getTranslation } from '../../../lib/i18n'

const supportEmail = process.env.NEXT_PUBLIC_PRIVACY_CONTACT_EMAIL ?? 'chaeyn@dgsw.hs.kr'

export default async function SupportPage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params
  const t = await getTranslation(locale)

  return (
    <main className="min-h-screen bg-[#050505] px-6 py-16 text-white md:px-12">
      <article className="mx-auto max-w-3xl space-y-10 text-base leading-8 text-[#e5e5e5]">
        <header>
          <Link href={`/${locale}`} className="text-sm text-[#a3a3a3] underline underline-offset-4">
            {t('support.back')}
          </Link>
          <h1 className="mt-6 text-3xl font-semibold text-white">{t('support.pageTitle')}</h1>
          <p className="mt-2">{t('support.intro')}</p>
        </header>

        <section>
          <h2 className="text-xl font-semibold text-white">{t('support.signIn.title')}</h2>
          <p>{t('support.signIn.body')}</p>
        </section>

        <section>
          <h2 className="text-xl font-semibold text-white">{t('support.accountDeletion.title')}</h2>
          <p>{t('support.accountDeletion.body')}</p>
          <ol className="mt-4 list-decimal space-y-1 pl-6">
            <li>{t('support.accountDeletion.step1')}</li>
            <li>{t('support.accountDeletion.step2')}</li>
            <li>{t('support.accountDeletion.step3')}</li>
          </ol>
          <p className="mt-4">{t('support.accountDeletion.note')}</p>
        </section>

        <section>
          <h2 className="text-xl font-semibold text-white">{t('support.face.title')}</h2>
          <p>{t('support.face.body')}</p>
        </section>

        <section>
          <h2 className="text-xl font-semibold text-white">{t('support.youtube.title')}</h2>
          <p>{t('support.youtube.body')}</p>
        </section>

        <section>
          <h2 className="text-xl font-semibold text-white">{t('support.contact.title')}</h2>
          <p>
            {t('support.contact.beforeEmail')} <a className="underline underline-offset-4" href={`mailto:${supportEmail}`}>{supportEmail}</a>{t('support.contact.afterEmail')}
          </p>
        </section>

        <nav className="flex gap-6 text-sm text-[#a3a3a3]" aria-label={t('support.navigation.label')}>
          <Link href={`/${locale}`} className="underline underline-offset-4 transition-colors hover:text-white">
            {t('support.navigation.home')}
          </Link>
          <Link href={`/${locale}/privacy`} className="underline underline-offset-4 transition-colors hover:text-white">
            {t('support.navigation.privacy')}
          </Link>
        </nav>
      </article>
    </main>
  )
}
