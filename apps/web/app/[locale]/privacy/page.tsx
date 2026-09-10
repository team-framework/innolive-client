import Link from 'next/link'

import { getTranslation } from '../../../lib/i18n'

const contactEmail = process.env.NEXT_PUBLIC_PRIVACY_CONTACT_EMAIL ?? 'chaeyn@dgsw.hs.kr'

function PolicySubsection({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="mt-4">
      <h3 className="text-lg text-white">{title}</h3>
      {children}
    </section>
  )
}

export default async function PrivacyPage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params
  const t = await getTranslation(locale)

  return (
    <main className="min-h-screen bg-[#050505] px-6 py-16 text-white md:px-12">
      <article className="mx-auto max-w-3xl space-y-10 text-base leading-8 text-[#e5e5e5]">
        <header>
          <Link href={`/${locale}`} className="text-sm text-[#a3a3a3] underline underline-offset-4">
            {t('privacy.back')}
          </Link>
          <h1 className="mt-6 text-3xl font-semibold text-white">{t('privacy.pageTitle')}</h1>
          <p className="mt-2 text-sm text-[#a3a3a3]">
            {t('privacy.effectiveDateLabel')} {t('privacy.effectiveDateValue')}
          </p>
        </header>

        <section>
          <h2 className="text-xl font-semibold text-white">{t('privacy.collection.title')}</h2>
          <PolicySubsection title={t('privacy.collection.preRegistrationTitle')}>
            <p>{t('privacy.collection.preRegistrationBody')}</p>
          </PolicySubsection>
          <PolicySubsection title={t('privacy.collection.accountTitle')}>
            <p>{t('privacy.collection.accountBody')}</p>
          </PolicySubsection>
          <PolicySubsection title={t('privacy.collection.experienceTitle')}>
            <p>{t('privacy.collection.experienceBody')}</p>
          </PolicySubsection>
          <PolicySubsection title={t('privacy.collection.youtubeTitle')}>
            <p>{t('privacy.collection.youtubeBody')}</p>
          </PolicySubsection>
        </section>

        <section>
          <h2 className="text-xl font-semibold text-white">{t('privacy.purpose.title')}</h2>
          <PolicySubsection title={t('privacy.collection.preRegistrationTitle')}>
            <p>{t('privacy.purpose.preRegistrationBody')}</p>
          </PolicySubsection>
          <PolicySubsection title={t('privacy.collection.accountTitle')}>
            <p>{t('privacy.purpose.accountBody')}</p>
          </PolicySubsection>
          <PolicySubsection title={t('privacy.collection.experienceTitle')}>
            <p>{t('privacy.purpose.experienceBody')}</p>
          </PolicySubsection>
          <PolicySubsection title={t('privacy.collection.youtubeTitle')}>
            <p>{t('privacy.purpose.youtubeBody')}</p>
          </PolicySubsection>
        </section>

        <section>
          <h2 className="text-xl font-semibold text-white">{t('privacy.retention.title')}</h2>
          <PolicySubsection title={t('privacy.collection.preRegistrationTitle')}>
            <p>{t('privacy.retention.preRegistrationBody')}</p>
          </PolicySubsection>
          <PolicySubsection title={t('privacy.collection.accountTitle')}>
            <p>{t('privacy.retention.accountStorage')}</p>
          </PolicySubsection>
          <PolicySubsection title={t('privacy.collection.experienceTitle')}>
            <p>{t('privacy.retention.experienceStorage')}</p>
            <p>{t('privacy.retention.experienceDeletion')}</p>
          </PolicySubsection>
        </section>

        <section>
          <h2 className="text-xl font-semibold text-white">{t('privacy.refusal.title')}</h2>
          <PolicySubsection title={t('privacy.collection.preRegistrationTitle')}>
            <p>{t('privacy.refusal.preRegistrationBody')}</p>
          </PolicySubsection>
          <PolicySubsection title={t('privacy.collection.experienceTitle')}>
            <p>{t('privacy.refusal.experienceBody')}</p>
          </PolicySubsection>
        </section>

        <section>
          <h2 className="text-xl font-semibold text-white">{t('privacy.accountDeletion.title')}</h2>
          <p>{t('privacy.accountDeletion.body')}</p>
          <p>{t('privacy.accountDeletion.externalCleanup')}</p>
        </section>

        <section>
          <h2 className="text-xl font-semibold text-white">{t('privacy.external.title')}</h2>
          <p>{t('privacy.external.body')}</p>
        </section>

        <section>
          <h2 className="text-xl font-semibold text-white">{t('privacy.contact.title')}</h2>
          <p>
            {t('privacy.contact.beforeEmail')} <a className="underline underline-offset-4" href={`mailto:${contactEmail}`}>{contactEmail}</a>{t('privacy.contact.afterEmail')}
          </p>
        </section>
      </article>
    </main>
  )
}
