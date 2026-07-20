import { ExperienceSection } from './ExperienceSection'
import { PreRegistrationSection } from './PreRegistrationSection'

export function HomePage() {
  return (
    <main className="overflow-hidden bg-[#050505] text-white">
      <section className="relative flex min-h-screen items-end overflow-hidden px-6 pb-16 pt-6 md:px-12 md:pb-20">
        <img
          src="/images/background.png"
          alt=""
          className="absolute inset-0 w-full h-full object-cover object-center"
        />
        <div className="absolute inset-0 bg-gradient-to-t from-black via-black/10 to-transparent"  />

        <img
          src="/figma/Logo_WT.svg"
          alt="InnoLive"
          className="absolute left-6 top-6 z-10 h-auto w-[120px] md:left-[clamp(36px,2.5vw,48px)] md:top-[clamp(46px,3.18vw,61px)] md:w-[clamp(124px,8.59vw,165px)]"
        />

        <div className="relative z-10 flex flex-col w-full items-start justify-between gap-8 md:flex-row md:items-end">
          <h1 className="font-neurimbo text-[clamp(56px,6.67vw,128px)] leading-[0.78] tracking-[-0.06em] text-shadow-[0_0_50px_rgba(0,0,0,0.35)]">
            당신의<br />
            라이브 방송을<br />
            안전하게
          </h1>
          <div className="flex shrink-0 gap-[clamp(6px,0.42vw,8px)]">
            <button type="button" className="rounded-lg bg-white px-[clamp(15px,1.04vw,20px)] py-[clamp(6px,0.42vw,8px)] text-[clamp(15px,1.04vw,20px)] text-black">
              사전등록
            </button>
            <button type="button" className="rounded-lg bg-black px-[clamp(15px,1.04vw,20px)] py-[clamp(6px,0.42vw,8px)] text-[clamp(15px,1.04vw,20px)] text-white">
              체험하기
            </button>
          </div>
        </div>
      </section>

      <ExperienceSection />
      <PreRegistrationSection />
    </main>
  )
}
