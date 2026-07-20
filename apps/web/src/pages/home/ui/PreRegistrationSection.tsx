export function PreRegistrationSection() {
  return (
    <section className="flex min-h-screen flex-col items-center justify-center gap-16 px-6 py-20 text-center">
      <div>
        <img
          src="/figma/GetEalryAccess.png"
          alt="Get early access"
          className="mx-auto w-[60vw] sm:w-[30vw]"
        />
        <p className="mt-[clamp(24px,1.67vw,32px)] text-base leading-none md:text-[clamp(18px,1.25vw,24px)] md:leading-none">
          InnoLive의 소식을 가장 빨리 접하고<br />
          정식 출시 시 다양한 혜택을 받아가세요.
        </p>
      </div>

      <form className="w-full text-left sm:w-[60vw] lg:w-[36.67vw]" onSubmit={(event) => event.preventDefault()}>
        <div className="flex gap-2 rounded-[114px] bg-white/90 p-1 md:gap-[clamp(12px,0.83vw,16px)]">
          <label className="min-w-0 flex-1 px-3 py-2.5 md:px-[clamp(9px,0.63vw,12px)] md:py-[clamp(7.5px,0.52vw,10px)]">
            <span className="sr-only">이메일</span>
            <input
              type="email"
              placeholder="이메일을 입력하세요."
              className="w-full bg-transparent text-base text-black outline-none placeholder:text-[#656565] md:text-[clamp(15px,1.04vw,20px)]"
            />
          </label>
          <button type="submit" className="shrink-0 rounded-[48px] bg-white px-4 py-3 text-base text-black md:px-[clamp(18px,1.25vw,24px)] md:py-[clamp(9px,0.63vw,12px)] md:text-[clamp(15px,1.04vw,20px)]">
            등록하기
          </button>
        </div>
        <label className="mt-[clamp(9px,0.63vw,12px)] flex cursor-pointer items-center gap-1 px-3 text-sm text-[#787878] md:px-[clamp(9px,0.63vw,12px)] md:text-[clamp(12px,0.83vw,16px)]">
          <input type="checkbox" className="size-3.5 accent-[#787878]" />
          <span>이메일 정보 수집에 동의합니다.</span>
          <button type="button" className="underline">자세히 보기</button>
        </label>
      </form>
    </section>
  )
}
