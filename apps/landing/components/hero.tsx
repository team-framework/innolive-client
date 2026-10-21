import Image from "next/image";
import { Button } from "@/components/button";
import { DownloadMenu } from "@/components/download-menu";
import { HeroDemoVideo } from "@/components/hero-demo-video";
import { localePath, type Locale } from "@/lib/locales";
import { getMessages } from "@/lib/messages";
import { PhoneArtwork } from "@/components/phone-artwork";
import { TypewriterHeroHeading } from "@/components/typewriter-hero-heading";

export function Hero({ locale }: { locale: Locale }) {
  const messages = getMessages(locale);
  return (
    <section
      className="flex h-fit w-full flex-col items-center overflow-x-clip px-[var(--page-gutter)] pb-12 pt-12 max-lg:pt-24 lg:min-h-svh lg:pb-0 lg:pt-[var(--header-height)]"
      aria-labelledby="hero-heading"
    >
      <div className="relative flex w-full max-w-[1640px] flex-col items-center gap-y-2 max-lg:top-12 lg:flex-1 lg:flex-row lg:items-stretch lg:justify-center lg:gap-x-16 min-[106.5rem]:gap-x-[161.5px]">
        <div className="flex w-full min-w-0 max-w-[752px] flex-col items-start lg:flex-1 lg:translate-x-12 lg:self-center min-[106.5rem]:w-[634px] min-[106.5rem]:flex-none min-[106.5rem]:translate-x-0">
          <div className="flex w-full max-w-[634px] flex-col gap-12">
            <div className="relative flex w-full flex-col gap-8">
              <TypewriterHeroHeading key={locale} copy={messages.hero} />
              <div className="hero-support-reveal w-full break-keep text-body-lg font-normal text-text-primary">
                <p>{messages.hero.support1}</p>
                <p>{messages.hero.support2}</p>
              </div>
            </div>
            <div className="hero-support-reveal hero-support-actions flex flex-wrap items-start gap-x-2.5 gap-y-4">
              <DownloadMenu trigger="hero" />
              <Button
                variant="secondary"
                showChevron={false}
                href={localePath(locale, "/try-out")}
                className="h-[53.2px] w-[95.9px] whitespace-nowrap max-md:w-auto md:h-[76px] md:w-[137px]"
              >
                {messages.hero.tryOut}
              </Button>
            </div>
          </div>
        </div>
        <div
          className="relative aspect-[726.4803/758.516] w-full min-w-0 max-w-[726.4803px] overflow-clip max-lg:top-16 lg:flex-1 lg:self-end min-[106.5rem]:w-[726.4803px] min-[106.5rem]:flex-none"
          aria-hidden="true"
        >
          <div
            className="absolute inset-[4.22%_0_0_44.88%] overflow-hidden rounded-tl-[clamp(30px,8.5vw,70px)] rounded-tr-[clamp(30px,8.5vw,70px)] lg:rounded-tl-[clamp(44px,5vw,70px)] lg:rounded-tr-[clamp(44px,4.3vw,70px)]"
          >
            <Image
              src="/mockups/hero-face-registration.webp"
              alt=""
              fill
              sizes="(min-width: 106.5rem) 400px, (min-width: 64rem) 28vw, (min-width: 48rem) 400px, 50vw"
              loading="eager"
              className="object-cover object-center"
            />
          </div>
          <PhoneArtwork
            frameClassName="inset-[4.22%_0_0_44.88%]"
            imageStyle={{
              height: "116.19%",
              width: "104.7%",
              left: "-2.35%",
              top: "-1.31%",
            }}
          />
          <HeroDemoVideo
            className="absolute inset-[0_44.88%_0_0] overflow-hidden rounded-tl-[clamp(30px,8.6vw,70px)] rounded-tr-[clamp(30px,8.6vw,70px)] bg-cover bg-center lg:rounded-tl-[clamp(44px,4.3vw,70px)] lg:rounded-tr-[clamp(44px,4.3vw,70px)]"
          />
          <PhoneArtwork
            frameClassName="inset-[0_44.85%_0_0] -top-[1px]"
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
