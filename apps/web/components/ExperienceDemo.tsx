'use client'

import { useTranslation } from 'react-i18next'

import { ExperiencePreview } from './ExperiencePreview'
import { FaceRegistrationControl } from './FaceRegistrationControl'
import { useExperienceConnection } from './useExperienceConnection'

const actionButtonClassName = 'cursor-pointer rounded-lg bg-white px-[clamp(15px,1.04vw,20px)] py-[clamp(6px,0.42vw,8px)] text-[clamp(15px,1.04vw,20px)] text-black transition-colors duration-200 hover:bg-[#d9d9d9] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white'

export function ExperienceDemo() {
  const { t } = useTranslation()
  const { state, statusText, videoRef, startExperience, endExperience, showStatusMessage } = useExperienceConnection(t)
  const isConnecting = state === 'connecting'
  const isConnected = state === 'connected'
  const primaryButtonText = isConnecting ? t('experience.buttons.cancel') : isConnected ? t('experience.buttons.end') : t('experience.buttons.start')

  return (
    <div className="flex w-full flex-col items-center gap-5">
      <ExperiencePreview state={state} statusText={statusText} videoRef={videoRef} />
      <div className="flex gap-[clamp(6px,0.42vw,8px)]">
        <button type="button" className={actionButtonClassName} onClick={isConnecting || isConnected ? endExperience : startExperience}>{primaryButtonText}</button>
        <FaceRegistrationControl buttonClassName={actionButtonClassName} onStatusMessage={showStatusMessage} />
      </div>
    </div>
  )
}
