import Link from 'next/link'

import { getTranslation } from '../../../lib/i18n'

const contactEmail = process.env.NEXT_PUBLIC_PRIVACY_CONTACT_EMAIL ?? 'chaeyn@dgsw.hs.kr'

export default async function PrivacyPage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params
  const t = await getTranslation(locale)

  return (
    <main className="min-h-screen bg-[#050505] px-6 py-16 text-white md:px-12">
      <article className="mx-auto max-w-3xl space-y-10 text-base leading-8 text-[#e5e5e5]">
        <div><Link href={`/${locale}`} className="text-sm text-[#a3a3a3] underline underline-offset-4">{t('privacy.back')}</Link><h1 className="mt-6 text-3xl font-semibold text-white">{t('privacy.pageTitle')}</h1><p className="mt-2 text-sm text-[#a3a3a3]">{t('privacy.effectiveDateLabel')} {t('privacy.effectiveDateValue')}</p></div>
        <section>
          <h2 className="text-xl font-semibold text-white">{t('privacy.collection.title')}</h2>
          <section className="mt-2">
            <h3 className="text-lg text-white">{t('privacy.collection.preRegistrationTitle')}</h3>
            <p>{t('privacy.collection.preRegistrationBody')}</p>
          </section>
          <section className="mt-2">
            <h3 className="text-lg text-white">{t('privacy.collection.experienceTitle')}</h3>
            <p>{t('privacy.collection.experienceBody')}</p>
          </section>
        </section>
        <section>
          <h2 className="text-xl font-semibold text-white">{t('privacy.purpose.title')}</h2>
          <section className="mt-2">
            <h3 className="text-lg text-white">{t('privacy.collection.preRegistrationTitle')}</h3>
            <p>{t('privacy.purpose.preRegistrationBody')}</p>
          </section>
          <section className="mt-2">
            <h3 className="text-lg text-white">{t('privacy.collection.experienceTitle')}</h3>
            <p>{t('privacy.purpose.experienceBody')}</p>
          </section>
        </section>
        <section>
          <h2 className="text-xl font-semibold text-white">{t('privacy.retention.title')}</h2>
          <section className="mt-2">
            <h3 className="text-lg text-white">{t('privacy.collection.preRegistrationTitle')}</h3>
            <p>{t('privacy.retention.preRegistrationBody')}</p>
          </section>
          <section className="mt-2">
            <h3 className="text-lg text-white">{t('privacy.collection.experienceTitle')}</h3>
            <p>{t('privacy.retention.experienceStorage')}</p>
            <p>{t('privacy.retention.experienceDeletion')}</p>
          </section>
        </section>
        <section>
          <h2 className="text-xl font-semibold text-white">{t('privacy.refusal.title')}</h2>
          <section className="mt-2">
            <h3 className="text-lg text-white">{t('privacy.collection.preRegistrationTitle')}</h3>
            <p>{t('privacy.refusal.preRegistrationBody')}</p>
          </section>
          <section className="mt-2">
            <h3 className="text-lg text-white">{t('privacy.collection.experienceTitle')}</h3>
            <p>{t('privacy.refusal.experienceBody')}</p>
          </section>
        </section>
        <section>
          <h2 className="text-xl font-semibold text-white">{t('privacy.contact.title')}</h2>
          <p>{t('privacy.contact.beforeEmail')} <a className="underline underline-offset-4" href={`mailto:${contactEmail}`}>{contactEmail}</a>{t('privacy.contact.afterEmail')}</p>
        </section>
      </article>
    </main>
  )
}
