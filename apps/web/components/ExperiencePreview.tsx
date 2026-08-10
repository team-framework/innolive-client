'use client'

import { useEffect, useState, type RefCallback } from 'react'
import { useTranslation } from 'react-i18next'

import type { ExperienceState } from './useExperienceConnection'

type ExperiencePreviewProps = {
  state: ExperienceState
  statusText: string
  processedVideoRef: RefCallback<HTMLVideoElement>
  localVideoRef: RefCallback<HTMLVideoElement>
  isBeforeAfterVisible: boolean
}

export function ExperiencePreview({ state, statusText, processedVideoRef, localVideoRef, isBeforeAfterVisible }: ExperiencePreviewProps) {
  const { t } = useTranslation()
  const [connectionDotCount, setConnectionDotCount] = useState(1)
  const isConnecting = state === 'connecting'
  const isConnected = state === 'connected'

  useEffect(() => {
    if (!isConnecting) {
      setConnectionDotCount(1)
      return
    }

    const interval = window.setInterval(() => {
      setConnectionDotCount((currentCount) => currentCount === 3 ? 1 : currentCount + 1)
    }, 500)

    return () => window.clearInterval(interval)
  }, [isConnecting])

  return (
    <>
      {isBeforeAfterVisible ? (
        <div className="grid w-full max-w-[1200px] gap-3 md:grid-cols-2">
          <section className="border border-[#5c5c5c] p-px">
            <p className="border border-[#5c5c5c] px-3 py-2 text-sm font-semibold text-white">{t('experience.video.before')}</p>
            <video ref={localVideoRef} autoPlay muted playsInline className="block aspect-video w-full scale-x-[-1] bg-black object-cover" aria-label={t('experience.video.localLabel')} />
          </section>
          <section className="border border-[#5c5c5c] p-px">
            <p className="border border-[#5c5c5c] px-3 py-2 text-sm font-semibold text-white">{t('experience.video.after')}</p>
            <video ref={processedVideoRef} autoPlay muted playsInline className="block aspect-video w-full scale-x-[-1] bg-black object-contain" aria-label={t('experience.video.processedLabel')} />
          </section>
        </div>
      ) : (
        <div className="w-full max-w-[850px] border border-[#5c5c5c] p-px">
          {isConnected ? (
            <video ref={processedVideoRef} autoPlay muted playsInline className="block aspect-video w-full border border-[#5c5c5c] bg-black object-contain" aria-label={t('experience.video.processedLabel')} />
          ) : (
            <div className="relative overflow-hidden border border-[#5c5c5c]">
              <img src="/images/live-pending.svg" alt={t('experience.video.pendingAlt')} className="block w-full" />
              {isConnecting && (
                <div className="absolute inset-0 flex flex-col items-center justify-center gap-4 bg-black/55 px-6 text-center backdrop-blur-[2px]">
                  <div className="flex items-end gap-2" aria-hidden="true">
                    {[0, 160, 320].map((delay) => (
                      <span key={delay} className="size-3 rounded-full bg-white animate-bounce" style={{ animationDelay: `${delay}ms` }} />
                    ))}
                  </div>
                  <p className="text-lg font-semibold text-white">{t('experience.status.connectingTitle', { dots: '.'.repeat(connectionDotCount) })}</p>
                </div>
              )}
            </div>
          )}
        </div>
      )}
      <div className="flex flex-col items-center">
        {!isConnecting && <p className="min-h-6 text-center text-sm text-[#c7c7c7]" role="status" aria-live="polite">{statusText}</p>}
        <p className="min-h-6 text-center text-sm text-[#c7c7c7]">{t('experience.video.notStored')}</p>
      </div>
    </>
  )
}
