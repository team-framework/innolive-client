import Image from "next/image";
import type { CSSProperties } from "react";

export type OverlayBox = {
  t: number;
  r: number;
  b: number;
  l: number;
};

export type FaceOverlaySpec = {
  box: OverlayBox;
  mask: string;
  blur: number;
};

export type IntroLettering = {
  src: string;
  alt: string;
  width: number;
  height: number;
  className: string;
};

export function nestBox(parent: OverlayBox, child: OverlayBox): OverlayBox {
  const width = 100 - parent.l - parent.r;
  const height = 100 - parent.t - parent.b;
  return {
    t: parent.t + (child.t / 100) * height,
    r: parent.r + (child.r / 100) * width,
    b: parent.b + (child.b / 100) * height,
    l: parent.l + (child.l / 100) * width,
  };
}

const photoSrc = "/intro/photo.png";
const photoSizes = "(max-aspect-ratio: 16/9) 177.7778vh, 100vw";

function overlaySrcFromMask(mask: string) {
  return mask.replace("/intro/masks/", "/intro/overlays/");
}

function FaceOverlay({ overlay }: { overlay: FaceOverlaySpec }) {
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
        src={overlaySrcFromMask(mask)}
        alt=""
        fill
        unoptimized
        className="object-fill"
      />
    </div>
  );
}

function SceneArtwork({
  overlays,
  priority,
}: {
  overlays: FaceOverlaySpec[];
  priority?: boolean;
}) {
  return (
    <>
      <Image
        src={photoSrc}
        alt=""
        width={1672}
        height={941}
        sizes={photoSizes}
        fetchPriority={priority ? "high" : undefined}
        className="absolute inset-0 size-full object-cover"
        aria-hidden="true"
      />
      {overlays.map((overlay) => (
        <FaceOverlay
          key={overlay.mask + overlay.box.l + overlay.box.t}
          overlay={overlay}
        />
      ))}
    </>
  );
}

export function IntroScene({
  label,
  overlays,
  lettering,
  fill = false,
  sceneIndex,
  priority = false,
}: {
  label: string;
  overlays: FaceOverlaySpec[];
  lettering: IntroLettering;
  fill?: boolean;
  sceneIndex?: number;
  priority?: boolean;
}) {
  const letteringClass = fill
    ? `absolute left-[5%] top-[12%] h-auto w-[70%] mix-blend-multiply min-[48rem]:left-[7.92%] min-[48rem]:top-[calc(50%-7.8%)] ${
        lettering.width === 374
          ? "min-[48rem]:w-[19.5%]"
          : "min-[48rem]:w-[24.7%]"
      }`
    : `${lettering.className} mix-blend-multiply`;
  const logoClass = fill
    ? "absolute bottom-[3.33%] left-1/2 h-auto w-24 -translate-x-1/2 object-contain min-[48rem]:w-[9rem]"
    : "absolute bottom-[3.33%] left-1/2 h-[3.52%] w-[7.5%] max-w-[9rem] -translate-x-1/2 object-contain";

  return (
    <section
      className={
        fill
          ? "relative h-full w-full overflow-clip bg-background-secondary [container-type:size]"
          : "relative aspect-[16/9] w-full overflow-clip bg-background-secondary"
      }
      aria-label={label}
      data-scene-index={sceneIndex}
    >
      {fill ? (
        <div
          className="absolute top-1/2 left-1/2 -translate-x-[72%] -translate-y-1/2 min-[48rem]:-translate-x-1/2"
          style={{
            width: "max(100cqw, 177.7778cqh)",
            height: "max(56.25cqw, 100cqh)",
          }}
        >
          <SceneArtwork overlays={overlays} priority={priority} />
        </div>
      ) : (
        <SceneArtwork overlays={overlays} priority={priority} />
      )}
      <Image
        src={lettering.src}
        alt={lettering.alt}
        width={lettering.width}
        height={lettering.height}
        className={letteringClass}
      />
      <Image
        src="/intro/logo.svg"
        alt="InnoLive"
        width={144}
        height={38}
        unoptimized
        className={logoClass}
      />
    </section>
  );
}
