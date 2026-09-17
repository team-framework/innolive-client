import Image from "next/image";
import type { CSSProperties } from "react";
import { Button } from "@/components/button";
import { DownloadMenu } from "@/components/download-menu";

const phoneSrc = "/landing/hero-phone.png";
const phoneWidth = 1359;
const phoneHeight = 2736;
const phoneSizes = "(min-width: 1704px) 400px, (min-width: 1024px) 40vw, 80vw";

function PhoneArtwork({
  frameClassName,
  imageStyle,
  fetchPriority,
}: {
  frameClassName: string;
  imageStyle: CSSProperties;
  fetchPriority?: "high";
}) {
  return (
    <div
      className={`absolute overflow-hidden rounded-tl-[min(74px,18.48%)] rounded-tr-[min(74px,18.48%)] bg-phone-fill ${frameClassName}`}
    >
      <Image
        src={phoneSrc}
        alt=""
        width={phoneWidth}
        height={phoneHeight}
        sizes={phoneSizes}
        fetchPriority={fetchPriority}
        className="absolute max-w-none"
        style={imageStyle}
      />
    </div>
  );
}

export function Hero() {
  return (
    <section
      className="flex w-full flex-col items-center overflow-x-clip px-[var(--page-gutter)] pt-12 lg:pt-16 min-[1704px]:min-h-[calc(100dvh-var(--header-block-desktop))] min-[1704px]:justify-end min-[1704px]:pt-[calc(321px-var(--header-block-desktop))]"
      aria-labelledby="hero-heading"
    >
      <div className="flex w-full max-w-[1640px] flex-col items-center gap-y-16 lg:flex-row lg:items-center lg:justify-center lg:gap-x-16 min-[1704px]:gap-x-[161.5px]">
        <div className="flex w-full min-w-0 max-w-[752px] flex-col items-start lg:flex-1 min-[1704px]:w-[752px] min-[1704px]:flex-none min-[1704px]:items-end min-[1704px]:pb-[178.516px]">
          <div className="flex w-full max-w-[634px] flex-col gap-12">
            <div className="relative flex w-full flex-col gap-8">
              <h1
                id="hero-heading"
                className="w-full break-words text-display font-bold tracking-tight text-text-primary"
              >
                주인공이 아니라면
                <br />
                과감하게{" "}
                <span className="relative inline-block">
                  가리기
                  <span
                    aria-hidden="true"
                    className="pointer-events-none absolute inset-[-0.15em_-0.12em] bg-overlay backdrop-blur-[7.5px]"
                  />
                </span>
                .
              </h1>
              <div className="w-full break-words text-body-lg font-normal text-text-primary">
                <p>야외 방송에 노출된 행인의 얼굴을 아직도 일일이 지우고 계신가요?</p>
                <p>InnoLive와 함께 더욱 안전한 방송을 만들어 보세요.</p>
              </div>
            </div>
            <div className="flex flex-wrap items-start gap-x-2.5 gap-y-4">
              <DownloadMenu trigger="hero" />
              <Button
                variant="secondary"
                showChevron={false}
                href="/try-out"
                className="h-[76px] w-[137px]"
              >
                Try Out
              </Button>
            </div>
          </div>
        </div>
        <div
          className="relative aspect-[726.48/758.516] w-full min-w-0 max-w-[726px] overflow-clip lg:flex-1 min-[1704px]:w-[726px] min-[1704px]:flex-none"
          aria-hidden="true"
        >
          <PhoneArtwork
            frameClassName="inset-[4.22%_0_0_44.88%]"
            imageStyle={{
              height: "116.19%",
              width: "104.7%",
              left: "-2.35%",
              top: "-1.31%",
            }}
          />
          <PhoneArtwork
            frameClassName="inset-[0_44.88%_0_0]"
            fetchPriority="high"
            imageStyle={{
              height: "110.49%",
              width: "103.95%",
              left: "-1.98%",
              top: "-1.11%",
            }}
          />
        </div>
      </div>
    </section>
  );
}
