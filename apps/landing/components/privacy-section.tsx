import Image from "next/image";

const phoneSrc = "/landing/privacy-phone.png";
const phoneSizes = "(min-width: 106.5rem) 438px, (min-width: 64rem) 40vw, 80vw";

const aiAccentStyle = {
  backgroundImage:
    "linear-gradient(90deg, rgb(108, 99, 255) 0%, rgb(79, 140, 255) 8.6538%, rgb(34, 211, 238) 12.981%)",
  backgroundRepeat: "no-repeat",
  backgroundSize: "100cqw 100%",
} as const;

export function PrivacySection() {
  return (
    <section
      className="flex w-full flex-col items-center bg-background-secondary px-[var(--page-gutter)] pb-10 pt-16 lg:pb-12 lg:pt-24 min-[106.5rem]:min-h-[67.5rem] min-[106.5rem]:pb-[39px] min-[106.5rem]:pt-40"
      aria-labelledby="privacy-heading"
    >
      <div className="flex w-full max-w-[1408px] flex-col items-center gap-y-12 lg:flex-row lg:items-center lg:justify-between lg:gap-x-16">
        <div className="flex w-full min-w-0 max-w-[500px] flex-col items-start gap-2.5 lg:flex-1 min-[106.5rem]:w-[500px] min-[106.5rem]:flex-none min-[106.5rem]:pb-[121px]">
          <h2
            id="privacy-heading"
            className="w-full break-words text-[clamp(2rem,1.05rem+4.2vw,4.25rem)] font-bold leading-[1.3] tracking-tight text-text-primary"
          >
            초상권 걱정 없는
            <br />
            <span className="inline-block max-w-full [container-type:inline-size]">
              <span
                className="bg-clip-text text-transparent"
                style={aiAccentStyle}
              >
                AI{" "}
              </span>
              비식별화 라이브
            </span>
          </h2>
          <div className="w-full break-keep text-body-lg font-normal text-text-primary">
            <p>실시간 AI 비식별화 기능을 통해 배경으로 등장하는</p>
            <p>행인의 초상권과 당신의 방송을 동시에 보호하세요.</p>
          </div>
        </div>
        <div
          className="relative aspect-[438/881] w-full min-w-0 max-w-[438px] overflow-clip lg:flex-1 min-[106.5rem]:w-[438px] min-[106.5rem]:flex-none"
          aria-hidden="true"
        >
          <Image
            src={phoneSrc}
            alt=""
            width={1359}
            height={2736}
            sizes={phoneSizes}
            className="absolute inset-0 size-full max-w-none object-cover"
          />
        </div>
      </div>
    </section>
  );
}
