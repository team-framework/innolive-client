import { Button } from "@/components/button";
import { DownloadMenu } from "@/components/download-menu";
import { PhoneArtwork } from "@/components/phone-artwork";
import { TypewriterHeroHeading } from "@/components/typewriter-hero-heading";

export function Hero() {
  return (
    <section
      className="flex w-full flex-col items-center overflow-x-clip px-[var(--page-gutter)] pt-12 lg:pt-16 min-[106.5rem]:min-h-[calc(100dvh-var(--header-block-desktop))] min-[106.5rem]:justify-end min-[106.5rem]:pt-[calc(321px-var(--header-block-desktop))]"
      aria-labelledby="hero-heading"
    >
      <div className="flex w-full max-w-[1640px] flex-col items-center gap-y-16 lg:flex-row lg:items-center lg:justify-center lg:gap-x-16 min-[106.5rem]:gap-x-[161.5px]">
        <div className="flex w-full min-w-0 max-w-[752px] flex-col items-start lg:translate-x-12 lg:flex-1 min-[106.5rem]:w-[752px] min-[106.5rem]:flex-none min-[106.5rem]:items-end min-[106.5rem]:pb-[178.516px]">
          <div className="flex w-full max-w-[634px] flex-col gap-12">
            <div className="relative flex w-full flex-col gap-8">
              <TypewriterHeroHeading />
              <div className="w-full break-keep text-body-lg font-normal text-text-primary">
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
          className="relative aspect-[726.4803/758.516] w-full min-w-0 max-w-[726.4803px] overflow-clip lg:flex-1 min-[106.5rem]:w-[726.4803px] min-[106.5rem]:flex-none"
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
