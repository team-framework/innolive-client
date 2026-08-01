'use client'

import {useEffect} from "react";
import { useTranslation } from 'react-i18next'

interface PrivacyPolicyModalProps {
  isOpen: boolean,
  onClose: () => void,
}

function PrivacyPolicyModal({isOpen, onClose}: PrivacyPolicyModalProps) {
  const { t } = useTranslation()

  useEffect(() => {
    if (!isOpen) {
      return;
    }

  }, [isOpen])

  if (!isOpen) {
    return null
  }

  const onDisagreed = () => {
    localStorage.setItem("facePrivacyPolicy", "false");
    onClose()
  }

  const onAgreed = () => {
    localStorage.setItem("facePrivacyPolicy", "true");
    onClose()
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/65 px-6 backdrop-blur-sm" role="presentation">
      <section className="flex flex-col gap-10 rounded-2xl overflow-y-auto bg-black px-20 py-15 text-center shadow-[0_20px_42px_rgba(0,0,0,0.55)]" role="dialog">
        <article className="mx-auto max-w-3xl space-y-10 text-base leading-8 text-[#e5e5e5]">
          <h1 className="mt-6 text-3xl font-semibold text-white">{t('privacy.consentModalTitle')}</h1>
          <section>
            <h2 className="text-xl font-semibold text-white">{t('privacy.collection.title')}</h2>
              <p>{t('privacy.collection.experienceBody')}</p>
          </section>
          <section>
            <h2 className="text-xl font-semibold text-white">{t('privacy.purpose.title')}</h2>
            <p>{t('privacy.purpose.experienceBody')}</p>
          </section>
          <section>
            <h2 className="text-xl font-semibold text-white">{t('privacy.retention.title')}</h2>
            <p>{t('privacy.retention.experienceStorage')}</p>
            <p>{t('privacy.retention.experienceDeletion')}</p>
          </section>
          <section>
            <h2 className="text-xl font-semibold text-white">{t('privacy.refusal.title')}</h2>
            <p>{t('privacy.refusal.experienceBody')}</p>
          </section>
        </article>
        <div className="flex justify-center gap-5">
          <button onClick={onAgreed} className="cursor-pointer rounded-lg bg-white px-[clamp(15px,1.04vw,20px)] py-[clamp(6px,0.42vw,8px)] text-[clamp(15px,1.04vw,20px)] text-black transition duration-200 hover:-translate-y-0.5 hover:bg-[#d9d9d9] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white">
            {t('privacy.agree')}
          </button>
          <button onClick={onDisagreed} className="cursor-pointer rounded-lg bg-black px-[clamp(15px,1.04vw,20px)] py-[clamp(6px,0.42vw,8px)] text-[clamp(15px,1.04vw,20px)] text-white transition duration-200 hover:-translate-y-0.5 hover:bg-[#2d2d2d] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white">
            {t('privacy.disagree')}
          </button>
        </div>
      </section>
    </div>
  )
}

export default PrivacyPolicyModal
