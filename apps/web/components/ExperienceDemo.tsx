'use client'

import { useTranslation } from 'react-i18next'
import { useEffect, useState } from 'react'

import { ExperiencePreview } from './ExperiencePreview'
import { FaceRegistrationControl } from './FaceRegistrationControl'
import { useExperienceConnection } from './useExperienceConnection'

const actionButtonClassName = 'cursor-pointer rounded-lg bg-white px-[clamp(15px,1.04vw,20px)] py-[clamp(6px,0.42vw,8px)] text-[clamp(15px,1.04vw,20px)] text-black transition-colors duration-200 hover:bg-[#d9d9d9] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white'

export function ExperienceDemo() {
  const { t } = useTranslation()
  const { state, statusText, sessionID, processedVideoRef, localVideoRef, startExperience, endExperience, showStatusMessage } = useExperienceConnection(t)
  const isConnecting = state === 'connecting'
  const isConnected = state === 'connected'
  const [isBeforeAfterVisible, setIsBeforeAfterVisible] = useState(false)
  const primaryButtonText = isConnecting ? t('experience.buttons.cancel') : isConnected ? t('experience.buttons.end') : t('experience.buttons.start')

  useEffect(() => {
    if (!isConnected) {
      setIsBeforeAfterVisible(false)
    }
  }, [isConnected])

  return (
    <div className="flex w-full flex-col items-center gap-5">
      <ExperiencePreview state={state} statusText={statusText} processedVideoRef={processedVideoRef} localVideoRef={localVideoRef} isBeforeAfterVisible={isBeforeAfterVisible} />
      <div className="flex gap-[clamp(6px,0.42vw,8px)]">
        <button type="button" className={actionButtonClassName} onClick={isConnecting || isConnected ? endExperience : startExperience}>{primaryButtonText}</button>
        {isConnected && <button type="button" aria-pressed={isBeforeAfterVisible} className={actionButtonClassName} onClick={() => setIsBeforeAfterVisible((currentValue) => !currentValue)}>{isBeforeAfterVisible ? t('experience.buttons.hideBeforeAfter') : t('experience.buttons.showBeforeAfter')}</button>}
        <FaceRegistrationControl buttonClassName={actionButtonClassName} sessionID={sessionID} onStatusMessage={showStatusMessage} />
      </div>
    </div>
  )
}
