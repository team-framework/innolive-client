import Link from 'next/link'

import { PreRegistrationForm } from '../components/PreRegistrationForm'

export default function HomePage() {
  return (
    <main className="overflow-hidden bg-[#050505] text-white">
      <section className="relative flex min-h-screen items-end overflow-hidden px-6 pb-16 pt-6 md:px-12 md:pb-20">
        <img src="/images/background.png" alt="" className="absolute inset-0 size-full object-cover object-center" />
        <div className="absolute inset-0 bg-gradient-to-t from-black via-black/10 to-transparent" />
        <img src="/figma/Logo_WT.svg" alt="InnoLive" className="absolute left-6 top-6 z-10 h-auto w-[120px] md:left-[clamp(36px,2.5vw,48px)] md:top-[clamp(46px,3.18vw,61px)] md:w-[clamp(124px,8.59vw,165px)]" />
        <div className="relative z-10 flex w-full flex-col items-start justify-between gap-8 md:flex-row md:items-end">
          <h1 className="font-neurimbo text-[clamp(56px,6.67vw,128px)] leading-[0.78] tracking-[-0.06em] text-shadow-[0_0_50px_rgba(0,0,0,0.35)]">당신의<br />라이브 방송을<br />안전하게</h1>
          <div className="flex shrink-0 gap-[clamp(6px,0.42vw,8px)]"><a href="#pre-registration" className="cursor-pointer rounded-lg bg-white px-[clamp(15px,1.04vw,20px)] py-[clamp(6px,0.42vw,8px)] text-[clamp(15px,1.04vw,20px)] text-black transition duration-200 hover:-translate-y-0.5 hover:bg-[#d9d9d9] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white">사전등록</a><a href="#experience" className="cursor-pointer rounded-lg bg-black px-[clamp(15px,1.04vw,20px)] py-[clamp(6px,0.42vw,8px)] text-[clamp(15px,1.04vw,20px)] text-white transition duration-200 hover:-translate-y-0.5 hover:bg-[#2d2d2d] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white">체험하기</a></div>
        </div>
      </section>

      <section id="experience" className="flex min-h-screen scroll-mt-4 flex-col items-center justify-center gap-10 py-8">
        <div className="text-center"><h2 className="font-neurimbo text-5xl leading-none tracking-[-0.05em] md:text-[50px]">체험하기</h2><p className="mt-[clamp(12px,0.83vw,16px)] text-base md:text-[clamp(18px,1.25vw,24px)]">체험을 시작하시려면 ‘시작하기’ 버튼을 눌러주세요.</p></div>
        <div className="w-full max-w-[850px] border border-[#5c5c5c] p-px"><img src="/figma/WaitingScreen.png" alt="InnoLive 체험 대기 화면" className="block w-full border border-[#5c5c5c]" /></div>
      </section>

      <section id="pre-registration" className="flex min-h-screen scroll-mt-4 flex-col items-center justify-center gap-16 px-6 py-20 text-center">
        <div><img src="/figma/GetEalryAccess.png" alt="Get early access" className="mx-auto w-[60vw] sm:w-[30vw]" /><p className="mt-[clamp(24px,1.67vw,32px)] text-base leading-tight md:text-[clamp(18px,1.25vw,24px)]">InnoLive의 소식을 가장 빨리 접하고<br />정식 출시 시 다양한 혜택을 받아가세요.</p></div>
        <PreRegistrationForm />
      </section>

      <footer className="px-6 py-8 text-center text-sm text-[#a3a3a3]"><Link href="/privacy" className="cursor-pointer underline underline-offset-4 transition-colors hover:text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white">개인정보처리방침</Link></footer>
    </main>
  )
}
