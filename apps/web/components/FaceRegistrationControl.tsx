'use client'

import { useCallback, useState } from 'react'
import { useTranslation } from 'react-i18next'

import { FaceRegistrationModal } from './FaceRegistrationModal'
import PrivacyPolicyModal from './PrivacyPolicyModal'
import { getExperienceServerEndpoints } from '../lib/experience-server'

type FaceRegistrationControlProps = {
  buttonClassName: string
  sessionID: string | null
  onStatusMessage: (message: string) => void
}

export function FaceRegistrationControl({ buttonClassName, sessionID, onStatusMessage }: FaceRegistrationControlProps) {
  const { t } = useTranslation()
  const [isPrivacyPolicyOpen, setIsPrivacyPolicyOpen] = useState(false)
  const [isFaceRegistrationOpen, setIsFaceRegistrationOpen] = useState(false)
  const [faceRegistrationURL, setFaceRegistrationURL] = useState<string | null>(null)

  const openFaceRegistration = useCallback(() => {
    if (localStorage.getItem('facePrivacyPolicy') !== 'true') {
      return
    }

    if (!sessionID) {
      onStatusMessage(t('faceRegistration.errors.sessionRequired'))
      return
    }

    try {
      const { sessionsURL } = getExperienceServerEndpoints(t)
      setFaceRegistrationURL(new URL('/reference-face', sessionsURL).toString())
      setIsFaceRegistrationOpen(true)
    } catch (error) {
      onStatusMessage(error instanceof Error ? error.message : t('experience.status.faceServerMissing'))
    }
  }, [onStatusMessage, sessionID, t])

  const openPrivacyPolicy = () => {
    if (localStorage.getItem('facePrivacyPolicy') === 'true') {
      openFaceRegistration()
      return
    }

    setIsPrivacyPolicyOpen(true)
  }

  return (
    <>
      <button type="button" className={buttonClassName} onClick={openPrivacyPolicy}>{t('experience.buttons.registerFace')}</button>
      <PrivacyPolicyModal isOpen={isPrivacyPolicyOpen} onClose={() => { setIsPrivacyPolicyOpen(false); openFaceRegistration() }} />
      {faceRegistrationURL && <FaceRegistrationModal isOpen={isFaceRegistrationOpen} registrationURL={faceRegistrationURL} sessionID={sessionID} onClose={() => setIsFaceRegistrationOpen(false)} />}
    </>
  )
}
