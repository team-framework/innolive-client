import Image from "next/image";
import type { CSSProperties } from "react";
import { heroPhoneInlineSrc } from "@/lib/hero-phone-inline";

const phoneSrc = "/landing/hero-phone.webp";
const phoneSizes = "(min-width: 106.5rem) 420px, (min-width: 64rem) 29vw, (min-width: 48rem) 420px, 52vw";

type PhoneArtworkProps = {
  frameClassName: string;
  imageStyle: CSSProperties;
  fetchPriority?: "high";
};

export function PhoneArtwork({
  frameClassName,
  imageStyle,
  fetchPriority,
}: PhoneArtworkProps) {
  const inlineForeground = fetchPriority === "high";
  return (
    <div
      className={`absolute overflow-hidden rounded-tl-[clamp(30px,8.5vw,70px)] lg:rounded-tl-[clamp(44px,5vw,70px)] rounded-tr-[clamp(30px,8.5vw,70px)] lg:rounded-tr-[clamp(44px,4.3vw,70px)] ${frameClassName}`}
    >
      <Image
        src={inlineForeground ? heroPhoneInlineSrc : phoneSrc}
        alt=""
        width={1359}
        height={2736}
        sizes={phoneSizes}
        unoptimized={inlineForeground}
        loading="eager"
        fetchPriority={fetchPriority ?? "low"}
        className="absolute max-w-none"
        style={imageStyle}
      />
    </div>
  );
}
