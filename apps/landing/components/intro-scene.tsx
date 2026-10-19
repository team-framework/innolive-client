import Image from "next/image";
import { SceneArtwork } from "./scene-artwork";

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
  clipPath?: string;
};

export type IntroLettering = {
  text: string;
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
  // The lettering PNGs were 116watermelon at 100px with 84px line pitch, shown 474px → 24.7% wide.
  const letteringClass = `whitespace-pre-line font-[family-name:var(--font-lettering)] text-[14.77cqw] font-black leading-[0.84] text-black [&:lang(ja)]:leading-none mix-blend-multiply min-[48rem]:text-[5.21cqw] ${
    fill
      ? "absolute left-[5%] top-[12%] min-[48rem]:left-[7.92%] min-[48rem]:top-[calc(50%-7.8%)]"
      : lettering.className
  }`;
  const logoClass = fill
    ? "absolute bottom-[3.33%] left-1/2 h-auto w-24 -translate-x-1/2 object-contain min-[48rem]:w-[9rem]"
    : "absolute bottom-[3.33%] left-1/2 h-[3.52%] w-[7.5%] max-w-[9rem] -translate-x-1/2 object-contain";

  return (
    <section
      className={
        fill
          ? "relative h-full w-full overflow-clip bg-background-secondary [container-type:size]"
          : "relative aspect-[16/9] w-full overflow-clip bg-background-secondary [container-type:inline-size]"
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
      <p className={letteringClass}>{lettering.text}</p>
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
