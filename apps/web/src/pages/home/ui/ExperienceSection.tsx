export function ExperienceSection() {
  return (
    <section className="flex min-h-screen flex-col items-center justify-center gap-10 py-8">
      <div className="text-center">
        <h2 className="font-neurimbo text-5xl leading-none tracking-[-0.05em] md:text-[50px]">체험하기</h2>
        <p className="mt-[clamp(12px,0.83vw,16px)] text-base md:text-[clamp(18px,1.25vw,24px)]">체험을 시작하시려면 ‘시작하기’ 버튼을 눌러주세요.</p>
      </div>

      <div className="w-full max-w-[850px] border border-[#5c5c5c] p-px">
        <img
          src="/figma/WaitingScreen.png"
          alt="InnoLive 체험 대기 화면"
          className="block w-full border border-[#5c5c5c]"
        />
      </div>

      <div className="flex w-full max-w-[352px] gap-[clamp(7.5px,0.52vw,10px)] xl:w-[18.33vw]">
        <button type="button" className="w-1/2 rounded-lg bg-white px-[clamp(15px,1.04vw,20px)] py-[clamp(7.5px,0.52vw,10px)] text-sm text-black">
          시작하기
        </button>
        <button type="button" className="w-1/2 rounded-lg bg-[#313131] px-[clamp(15px,1.04vw,20px)] py-[clamp(7.5px,0.52vw,10px)] text-sm text-white md:text-md">
          얼굴 등록하기
        </button>
      </div>
    </section>
  )
}
