'use client'

import Link from 'next/link'
import { useTranslation } from 'react-i18next'
import { useState, type PointerEvent } from 'react'
import { PreRegistrationForm } from '../../components/PreRegistrationForm'
import { ExperienceDemo } from '../../components/ExperienceDemo'
import { locales } from '../../proxy'

export default function HomePage() {
  const { t, i18n } = useTranslation()
  const locale = i18n.resolvedLanguage ?? 'ko'
  const [ isSelectLanguage, setIsSelectLanguage ] = useState(false)
  // 두 이미지가 나뉘는 비교선 위치를 0부터 100까지의 비율로 관리
  const [originalImagePosition, setOriginalImagePosition] = useState(36)

  const updateOriginalImagePosition = (clientX: number, container: HTMLElement) => {
    const bounds = container.getBoundingClientRect()
    // 포인터의 가로 좌표를 이미지 전체 폭 기준의 비율로 바꾸고 화면 범위를 넘지 않게 제한
    const position = ((clientX - bounds.left) / bounds.width) * 100
    setOriginalImagePosition(Math.round(Math.min(100, Math.max(0, position))))
  }

  const handleComparisonPointerDown = (event: PointerEvent<HTMLDivElement>) => {
    // 드래그가 영역 밖으로 나가도 계속 추적할 수 있도록 포인터를 현재 영역에 고정
    event.currentTarget.setPointerCapture(event.pointerId)
    updateOriginalImagePosition(event.clientX, event.currentTarget)
  }

  const handleComparisonPointerMove = (event: PointerEvent<HTMLDivElement>) => {
    // 비교 슬라이더가 포인터를 잡은 경우에만 비교선 위치를 갱신
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      updateOriginalImagePosition(event.clientX, event.currentTarget)
    }
  }

  return (
    <main className="select-none overflow-hidden bg-[#050505] text-white">
      <section className="relative flex min-h-screen items-end overflow-hidden px-6 pb-16 pt-6 md:px-12 md:pb-20">
        {/* 전체 배경 위에 두 번째 이미지를 비교선 왼쪽에만 덮어 Before/After 효과를 만듦. */}
        <img src="/images/background.png" alt="" className="absolute inset-0 size-full object-cover object-center" />
        <img
          src="/images/de-identified-background.png"
          alt=""
          className="absolute inset-0 size-full object-cover object-center"
          style={{ clipPath: `inset(0 ${100 - originalImagePosition}% 0 0)` }}
        />
        <div
          aria-hidden="true"
          className="pointer-events-none absolute inset-y-0 z-[1] w-px bg-white/90 shadow-[0_0_12px_rgba(255,255,255,0.75)]"
          style={{ left: `${originalImagePosition}%` }}
        >
          <span className="absolute left-1/2 top-1/2 grid size-11 -translate-x-1/2 -translate-y-1/2 place-items-center rounded-full border border-white/70 bg-black/35 backdrop-blur-sm">
            <span className="h-4 w-5 border-x border-white/90" />
          </span>
        </div>
        <div
          aria-label="원본과 비식별화 이미지 비교 위치"
          aria-valuemax={100}
          aria-valuemin={0}
          aria-valuenow={Math.round(originalImagePosition)}
          aria-valuetext={`원본 이미지 ${originalImagePosition}%, 비식별화 이미지 ${100 - originalImagePosition}%`}
          className="absolute inset-0 z-[2] cursor-ew-resize touch-none focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-white"
          onPointerDown={handleComparisonPointerDown}
          onPointerMove={handleComparisonPointerMove}
          role="slider"
          tabIndex={0}
        />
        <div className="absolute inset-0 bg-gradient-to-t from-black via-black/10 to-transparent" />
        <header>
          <img src="/images/logo-white.svg" alt="InnoLive" className="absolute left-6 top-6 z-10 h-auto w-[120px] md:left-[clamp(36px,2.5vw,48px)] md:top-[clamp(46px,3.18vw,61px)] md:w-[clamp(124px,8.59vw,165px)]" />
          <div className="flex flex-col items-center absolute right-6 top-6 z-10 md:right-[clamp(36px,2.5vw,48px)] md:top-12">
            <button onClick={() => setIsSelectLanguage((prev) => !prev)} className="cursor-pointer flex items-center gap-2 px-2 py-1 rounded-[100rem] hover:bg-[#FFFFFF33] h-auto">
              <img src="/images/language.svg" alt="select language" className="w-4" />
              <span className="text-sm">{locales.find((element) => element.code === locale)?.label}</span>
            </button>
            {isSelectLanguage ? (
              <ul className="relative top-1 text-sm">
                {locales.map((locale) => (
                  <Link href={`/${locale.code}`} key={locale.code} className="cursor-pointer flex p-1 hover:bg-[#FFFFFF33] rounded-md">{locale.label}</Link>
                ))}
              </ul>
            ) : null}
          </div>
        </header>
        <div className="relative z-10 flex w-full flex-col items-start justify-between gap-8 md:flex-row md:items-end">
          <h1 className="font-neurimbo text-[clamp(56px,6.67vw,128px)] leading-[0.78] tracking-[-0.06em] text-shadow-[0_0_50px_rgba(0,0,0,0.35)]">{t('home.hero.line1')}<br />{t('home.hero.line2')}<br />{t('home.hero.line3')}</h1>
          <div className="flex shrink-0 gap-[clamp(6px,0.42vw,8px)]"><a href="#pre-registration" className="cursor-pointer rounded-lg bg-white px-[clamp(15px,1.04vw,20px)] py-[clamp(6px,0.42vw,8px)] text-[clamp(15px,1.04vw,20px)] text-black transition duration-200 hover:-translate-y-0.5 hover:bg-[#d9d9d9] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white">{t('home.navigation.preRegistration')}</a><a href="#experience" className="cursor-pointer rounded-lg bg-black px-[clamp(15px,1.04vw,20px)] py-[clamp(6px,0.42vw,8px)] text-[clamp(15px,1.04vw,20px)] text-white transition duration-200 hover:-translate-y-0.5 hover:bg-[#2d2d2d] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white">{t('home.navigation.experience')}</a></div>
        </div>
      </section>

      <section id="experience" className="flex min-h-screen scroll-mt-4 flex-col items-center justify-center gap-10 py-8">
        <div className="text-center"><h2 className="font-neurimbo text-5xl leading-none tracking-[-0.05em] md:text-[50px]">{t('home.experience.title')}</h2><p className="mt-[clamp(12px,0.83vw,16px)] text-base md:text-[clamp(18px,1.25vw,24px)]">{t('home.experience.description')}</p></div>
        <ExperienceDemo />
      </section>

      <section id="pre-registration" className="flex min-h-screen scroll-mt-4 flex-col items-center justify-center gap-16 px-6 py-20 text-center">
        <div>
          <h2 className="lenticular-title" aria-label="Get early access">
            <span>Get early</span>
            <span>access</span>
          </h2>
          <p className="mt-[clamp(24px,1.67vw,32px)] text-base leading-tight md:text-[clamp(18px,1.25vw,24px)]">{t('home.earlyAccess.descriptionLine1')}<br />{t('home.earlyAccess.descriptionLine2')}</p>
        </div>
        <PreRegistrationForm />
      </section>

      <footer className="px-6 py-8 text-center text-sm text-[#a3a3a3]"><Link href={`/${locale}/privacy`} className="cursor-pointer underline underline-offset-4 transition-colors hover:text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white">{t('home.privacyPolicy')}</Link></footer>
    </main>
  )
}
