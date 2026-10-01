import {
  IntroScene,
  type FaceOverlaySpec,
  type IntroLettering,
} from "@/components/intro-scene";

const letteringSafe: IntroLettering = {
  src: "/intro/lettering-safe.png",
  alt: "당신의 방송을 안전하게.",
  width: 474,
  height: 158,
  className:
    "absolute left-[5%] top-[36%] h-auto w-[58%] min-[48rem]:left-[7.92%] min-[48rem]:top-[calc(50%-7.8%)] min-[48rem]:w-[24.7%]",
};

const letteringFocus: IntroLettering = {
  src: "/intro/lettering-focus.png",
  alt: "당신에게만 집중.",
  width: 374,
  height: 158,
  className:
    "absolute left-[5%] top-[36%] h-auto w-[50%] min-[48rem]:left-[7.92%] min-[48rem]:top-[calc(50%-7.8%)] min-[48rem]:w-[19.5%]",
};

// Face coordinates in the 1672 × 941 source photo; the presenter stays visible.
const faces: FaceOverlaySpec[] = [
  { box: { t: 0.5, r: 38.1, b: 90.2, l: 57.4 }, mask: "/intro/masks/face.svg", blur: 20 },
  { box: { t: -0.5, r: 15.4, b: 91.1, l: 80.4 }, mask: "/intro/masks/face.svg", blur: 20 },
  { box: { t: 15.2, r: 3.5, b: 75.4, l: 91.6 }, mask: "/intro/masks/face.svg", blur: 20 },
  { box: { t: 47.1, r: -0.8, b: 41.9, l: 95.5 }, mask: "/intro/masks/face.svg", blur: 20 },
  { box: { t: 74.4, r: 14.4, b: 14.7, l: 79.9 }, mask: "/intro/masks/face.svg", blur: 20 },
  { box: { t: 58.8, r: 41.6, b: 30.4, l: 53.1 }, mask: "/intro/masks/face.svg", blur: 20 },
];

export const introScenes = Array.from({ length: faces.length + 1 }, (_, index) => ({
  id: `p${index + 1}`,
  label: `인트로 ${index + 1}. ${index === faces.length ? "당신에게만 집중." : "당신의 방송을 안전하게."}`,
  lettering: index === faces.length ? letteringFocus : letteringSafe,
  overlays: faces.slice(0, index),
}));

export function IntroScenes() {
  return (
    <div className="flex w-full flex-col">
      {introScenes.map((scene, index) => (
        <IntroScene
          key={scene.id}
          label={scene.label}
          overlays={scene.overlays}
          lettering={scene.lettering}
          sceneIndex={index}
        />
      ))}
    </div>
  );
}
