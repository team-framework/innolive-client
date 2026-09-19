import Image from "next/image";
import type { CSSProperties } from "react";

const phoneSrc = "/landing/hero-phone.png";
const phoneSizes = "(min-width: 106.5rem) 400px, (min-width: 64rem) 40vw, 80vw";

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
  return (
    <div
      className={`absolute overflow-hidden rounded-tl-[min(74px,18.48%)] rounded-tr-[min(74px,18.48%)] bg-phone-fill ${frameClassName}`}
    >
      <Image
        src={phoneSrc}
        alt=""
        width={1359}
        height={2736}
        sizes={phoneSizes}
        fetchPriority={fetchPriority}
        className="absolute max-w-none"
        style={imageStyle}
      />
    </div>
  );
}
