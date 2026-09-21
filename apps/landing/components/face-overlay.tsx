import Image from "next/image";
import type { CSSProperties } from "react";
import type { FaceOverlaySpec } from "./intro-scene";

const photoSrc = "/intro/photo.png";
const photoSizes = "(max-aspect-ratio: 16/9) 177.7778vh, 100vw";

export function FaceOverlay({ overlay }: { overlay: FaceOverlaySpec }) {
  const { box, mask, blur } = overlay;
  const width = 100 - box.l - box.r;
  const height = 100 - box.t - box.b;
  const photoStyle: CSSProperties = {
    top: `${(-box.t / height) * 100}%`,
    left: `${(-box.l / width) * 100}%`,
    width: `${(100 / width) * 100}%`,
    height: `${(100 / height) * 100}%`,
    filter: `blur(${blur}px)`,
  };
  const maskStyle: CSSProperties = {
    WebkitMaskImage: `url(${mask})`,
    maskImage: `url(${mask})`,
    maskMode: "alpha",
    WebkitMaskRepeat: "no-repeat",
    maskRepeat: "no-repeat",
    WebkitMaskSize: "100% 100%",
    maskSize: "100% 100%",
  };

  return (
    <div
      className="pointer-events-none absolute overflow-hidden"
      style={{
        clipPath: overlay.clipPath,
        top: `${box.t}%`,
        right: `${box.r}%`,
        bottom: `${box.b}%`,
        left: `${box.l}%`,
      }}
      aria-hidden="true"
    >
      <div className="absolute inset-0" style={maskStyle}>
        <Image
          src={photoSrc}
          alt=""
          width={1672}
          height={941}
          sizes={photoSizes}
          className="absolute max-w-none"
          style={photoStyle}
        />
      </div>
      <Image
        src={mask.replace("/intro/masks/", "/intro/overlays/")}
        alt=""
        fill
        unoptimized
        className="object-fill"
      />
    </div>
  );
}

