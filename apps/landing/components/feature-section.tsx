import Image from "next/image";

const cardClass = "overflow-clip rounded-[12px]";

const platformFill = {
  backgroundImage:
    "linear-gradient(90deg, rgba(0, 0, 0, 0.05) 0%, rgba(0, 0, 0, 0.05) 100%), linear-gradient(112.94deg, rgb(255, 1, 50) 15%, rgb(251, 255, 249) 50%, rgb(0, 255, 163) 85%)",
} as const;

const portraitCrop = {
  height: "199.7%",
  width: "100.04%",
  left: "-0.02%",
  top: 0,
} as const;

export function FeatureSection() {
  return (
    <section
      className="flex w-full flex-col items-center px-[var(--page-gutter)] pb-16 pt-16 lg:pb-24 lg:pt-24 min-[106.5rem]:pb-[142px] min-[106.5rem]:pt-40"
      aria-labelledby="features-heading"
    >
      <div className="flex w-full max-w-[1408px] flex-col items-stretch gap-12 min-[106.5rem]:gap-[107px]">
        <div className="flex w-full flex-col items-start justify-between gap-6 lg:flex-row lg:items-start lg:gap-x-16">
          <h2
            id="features-heading"
            className="break-words text-[clamp(2rem,1.05rem+4.2vw,4.25rem)] font-bold leading-[1.3] tracking-tight text-text-primary"
          >
            기본은 충실하게
          </h2>
          <div className="max-w-[494px] break-keep text-body-lg font-normal text-text-primary">
            <p>방송 어플리케이션의 기본 역할을 충실히 수행합니다.</p>
            <p>원하는 플랫폼을 선택하고 손쉽게 방송을 시작하세요!</p>
          </div>
        </div>

        <div className="flex w-full flex-col gap-6">
          <div className="flex w-full flex-col gap-5">
            <div className="flex w-full flex-col items-stretch justify-center gap-4 lg:flex-row lg:items-stretch">
              <div
                className={`${cardClass} flex min-h-[14.5rem] w-full flex-col items-center justify-center gap-[15px] bg-gradient-to-b from-[#2563eb] to-[#b3bed7] p-8 lg:min-h-[23.125rem] lg:flex-1 lg:min-w-0 min-[106.5rem]:h-[370px] min-[106.5rem]:max-w-[518px] min-[106.5rem]:flex-none min-[106.5rem]:p-8`}
              >
                <p className="text-[clamp(3.5rem,1.6rem+8vw,7.5rem)] font-extrabold leading-none text-text-reversed">
                  25ms
                </p>
                <div className="flex items-start gap-1">
                  <p className="break-keep text-[clamp(1.125rem,0.85rem+1.4vw,2rem)] font-extrabold leading-none text-text-reversed">
                    비식별화 최대 지연시간
                  </p>
                  <span className="relative mt-0.5 inline-block h-[11.667px] w-[10.104px] shrink-0">
                    <span className="absolute inset-[-7.14%_-8.25%]">
                      <Image
                        src="/landing/features-asterisk.svg"
                        alt=""
                        width={12}
                        height={13}
                        unoptimized
                        className="size-full max-w-none"
                      />
                    </span>
                  </span>
                </div>
              </div>

              <div
                className={`${cardClass} flex min-h-[14.5rem] w-full flex-col items-center justify-center gap-[19px] p-8 lg:min-h-[23.125rem] lg:flex-1 lg:min-w-0 min-[106.5rem]:h-[370px] min-[106.5rem]:max-w-[874px] min-[106.5rem]:flex-none`}
                style={platformFill}
              >
                <div className="flex w-full max-w-[810px] flex-wrap content-center items-center justify-center gap-x-6 gap-y-6 min-[106.5rem]:gap-x-[100px]">
                  <div className="relative aspect-[307/134] w-[min(100%,19.1875rem)]">
                    <Image
                      src="/landing/youtube-logo.png"
                      alt="YouTube"
                      width={1705}
                      height={573}
                      sizes="307px"
                      className="absolute inset-0 size-full max-w-none object-cover"
                    />
                  </div>
                  <div className="relative aspect-[268/84] w-[min(100%,16.772rem)]">
                    <Image
                      src="/landing/chzzk-logo.png"
                      alt="치지직"
                      width={1920}
                      height={601}
                      sizes="268px"
                      className="absolute inset-0 size-full max-w-none object-cover"
                    />
                  </div>
                </div>
                <p className="w-full max-w-[810px] break-keep text-center text-[clamp(1.125rem,0.85rem+1.4vw,2rem)] font-extrabold leading-none text-text-primary">
                  다양한 플랫폼에 직접 송출
                </p>
              </div>
            </div>

            <div className="flex w-full flex-col items-stretch justify-center gap-4 lg:flex-row lg:items-stretch">
              <div
                className={`${cardClass} relative aspect-[874/370] w-full bg-background-secondary lg:flex-1 lg:min-w-0 min-[106.5rem]:h-[370px] min-[106.5rem]:max-w-[874px] min-[106.5rem]:flex-none min-[106.5rem]:aspect-auto`}
                aria-label="AI 비식별화 라이브"
              >
                <div className="absolute inset-[39.68%_8.62%_40.08%_8.44%]">
                  <Image
                    src="/landing/features-lettering.svg"
                    alt=""
                    width={725}
                    height={75}
                    unoptimized
                    className="absolute inset-0 size-full max-w-none"
                  />
                </div>
                <div className="absolute inset-[10%_8.35%_0_30.78%]">
                  <div className="absolute inset-0 overflow-hidden">
                    <Image
                      src="/landing/features-portrait.png"
                      alt=""
                      width={1122}
                      height={1402}
                      sizes="(min-width: 106.5rem) 532px, 80vw"
                      className="absolute max-w-none"
                      style={portraitCrop}
                    />
                  </div>
                  <div className="absolute inset-[15.92%_42.86%_36.04%_35.62%]">
                    <div className="absolute inset-[-2.5%_-3.49%] bg-[#d9d9d903] backdrop-blur-[12px] [mask-image:url(/landing/features-face-blur.svg)] [mask-mode:alpha] [mask-repeat:no-repeat] [mask-size:100%_100%] [-webkit-mask-image:url(/landing/features-face-blur.svg)] [-webkit-mask-repeat:no-repeat] [-webkit-mask-size:100%_100%]" />
                    <div className="absolute inset-[-2.5%_-3.49%]">
                      <Image
                        src="/landing/features-face-blur.svg"
                        alt=""
                        width={123}
                        height={168}
                        unoptimized
                        className="size-full max-w-none"
                      />
                    </div>
                  </div>
                </div>
              </div>

              <div className="flex w-full min-w-0 flex-col gap-5 lg:flex-1 min-[106.5rem]:w-[518px] min-[106.5rem]:max-w-[518px] min-[106.5rem]:flex-none">
                <div
                  className={`${cardClass} flex min-h-[10.875rem] w-full flex-col items-center justify-center gap-2.5 bg-background-secondary min-[106.5rem]:h-[174px] min-[106.5rem]:min-h-[174px]`}
                >
                  <p className="text-[clamp(2.5rem,1.4rem+4vw,4rem)] font-extrabold leading-none text-text-primary">
                    1080p
                  </p>
                  <p className="text-center text-xl font-medium leading-none text-text-primary">
                    고해상도 라이브
                  </p>
                </div>
                <div
                  className={`${cardClass} flex min-h-[10.875rem] w-full flex-col items-center justify-center gap-2.5 bg-background-secondary min-[106.5rem]:h-[176px] min-[106.5rem]:min-h-[174px]`}
                >
                  <p className="bg-gradient-to-b from-[#10b981] to-[#a7ffe2] bg-clip-text text-[clamp(2.5rem,1.4rem+4vw,4rem)] font-extrabold leading-none text-transparent">
                    FREEE
                  </p>
                  <p className="text-center text-xl font-medium leading-none text-text-primary">
                    무료 요금제로 LITE하게**
                  </p>
                </div>
              </div>
            </div>
          </div>

          <div className="flex w-full flex-col break-keep text-[length:clamp(1rem,0.9rem+0.4vw,1.25rem)] font-normal leading-[1.3] tracking-tight text-text-secondary">
            <p>*서버-AI간 지연시간 측정 자료, 네트워크 환경 및 기기에 따라 변동 가능</p>
            <p>**무료 요금제의 경우 사용에 일부 제한이 있을 수 있습니다.</p>
          </div>
        </div>
      </div>
    </section>
  );
}
