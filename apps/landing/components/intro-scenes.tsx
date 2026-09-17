import {
  IntroScene,
  nestBox,
  type FaceOverlaySpec,
  type IntroLettering,
  type OverlayBox,
} from "@/components/intro-scene";

const blob = 45;
const stripe = 20;

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

const v1: OverlayBox = { t: 4.17, r: 15.26, b: 65.14, l: 77.84 };
const v1Inner = nestBox(v1, { t: 0.11, r: 0, b: 0, l: 0.61 });
const centerFace: OverlayBox = { t: 5.83, r: 35.23, b: 61.81, l: 56.3 };
const rightMid: OverlayBox = { t: 21.44, r: 3.64, b: 43.47, l: 87.42 };
const rightLow: OverlayBox = { t: 48.64, r: -0.03, b: 6.81, l: 91.54 };

const g3: OverlayBox = { t: -0.56, r: 15.62, b: 92.04, l: 57.5 };
const g4: OverlayBox = { t: -0.56, r: 3.23, b: 77.13, l: 57.5 };
const g5: OverlayBox = { t: -0.56, r: -1.31, b: 43.98, l: 57.5 };

const scenes: {
  id: string;
  label: string;
  lettering: IntroLettering;
  overlays: FaceOverlaySpec[];
}[] = [
  {
    id: "p1",
    label: "인트로 1. 당신의 방송을 안전하게.",
    lettering: letteringSafe,
    overlays: [],
  },
  {
    id: "p2",
    label: "인트로 2. 당신의 방송을 안전하게.",
    lettering: letteringSafe,
    overlays: [
      { box: centerFace, mask: "/intro/masks/s2-rect.svg", blur: blob },
      {
        box: { t: -0.09, r: 38.33, b: 92.04, l: 57.5 },
        mask: "/intro/masks/s2-g7.svg",
        blur: stripe,
      },
    ],
  },
  {
    id: "p3",
    label: "인트로 3. 당신의 방송을 안전하게.",
    lettering: letteringSafe,
    overlays: [
      { box: v1Inner, mask: "/intro/masks/s3-v1.svg", blur: blob },
      { box: centerFace, mask: "/intro/masks/s3-rect.svg", blur: blob },
      {
        box: nestBox(g3, { t: 0, r: 0, b: 9.78, l: 84.49 }),
        mask: "/intro/masks/s3-f10.svg",
        blur: stripe,
      },
      {
        box: nestBox(g3, { t: 5.43, r: 84.49, b: 0, l: 0 }),
        mask: "/intro/masks/s3-f9.svg",
        blur: stripe,
      },
    ],
  },
  {
    id: "p4",
    label: "인트로 4. 당신의 방송을 안전하게.",
    lettering: letteringSafe,
    overlays: [
      { box: v1Inner, mask: "/intro/masks/s4-v1.svg", blur: blob },
      { box: centerFace, mask: "/intro/masks/s4-rect.svg", blur: blob },
      { box: rightMid, mask: "/intro/masks/s4-v2.svg", blur: blob },
      {
        box: nestBox(g4, { t: 57.71, r: 0, b: 0, l: 85.01 }),
        mask: "/intro/masks/s4-f11.svg",
        blur: stripe,
      },
      {
        box: nestBox(g4, { t: 0, r: 31.56, b: 67.19, l: 57.82 }),
        mask: "/intro/masks/s4-f10.svg",
        blur: stripe,
      },
      {
        box: nestBox(g4, { t: 1.98, r: 89.39, b: 63.64, l: 0 }),
        mask: "/intro/masks/s4-f9.svg",
        blur: stripe,
      },
    ],
  },
  {
    id: "p5",
    label: "인트로 5. 당신의 방송을 안전하게.",
    lettering: letteringSafe,
    overlays: [
      { box: v1Inner, mask: "/intro/masks/s5-v1.svg", blur: blob },
      { box: centerFace, mask: "/intro/masks/s5-rect.svg", blur: blob },
      { box: rightMid, mask: "/intro/masks/s5-v4.svg", blur: blob },
      { box: rightLow, mask: "/intro/masks/s5-v5.svg", blur: blob },
      {
        box: nestBox(g5, { t: 79.87, r: 0, b: 0, l: 85.71 }),
        mask: "/intro/masks/s5-f12.svg",
        blur: stripe,
      },
      {
        box: nestBox(g5, { t: 23.9, r: 10.36, b: 58.59, l: 76.2 }),
        mask: "/intro/masks/s5-f11.svg",
        blur: stripe,
      },
      {
        box: nestBox(g5, { t: 0, r: 38.66, b: 86.42, l: 51.83 }),
        mask: "/intro/masks/s5-f10.svg",
        blur: stripe,
      },
      {
        box: nestBox(g5, { t: 0.82, r: 90.49, b: 84.94, l: 0 }),
        mask: "/intro/masks/s5-f9.svg",
        blur: stripe,
      },
    ],
  },
  {
    id: "p6",
    label: "인트로 6. 당신의 방송을 안전하게.",
    lettering: letteringSafe,
    overlays: [
      { box: v1Inner, mask: "/intro/masks/s6-v1.svg", blur: blob },
      { box: centerFace, mask: "/intro/masks/s6-rect.svg", blur: blob },
      { box: rightMid, mask: "/intro/masks/s6-v4.svg", blur: blob },
      { box: rightLow, mask: "/intro/masks/s6-v5.svg", blur: blob },
      {
        box: { t: -0.56, r: -1.31, b: 0.05, l: 57.5 },
        mask: "/intro/masks/s6-g7.svg",
        blur: stripe,
      },
    ],
  },
  {
    id: "p7",
    label: "인트로 7. 당신에게만 집중.",
    lettering: letteringFocus,
    overlays: [
      { box: v1Inner, mask: "/intro/masks/s7-v1.svg", blur: blob },
      { box: centerFace, mask: "/intro/masks/s7-rect.svg", blur: blob },
      { box: rightLow, mask: "/intro/masks/s7-v3.svg", blur: blob },
      {
        box: { t: 78.84, r: 9.77, b: 0.05, l: 76.15 },
        mask: "/intro/masks/s7-v4.svg",
        blur: blob,
      },
      { box: rightMid, mask: "/intro/masks/s7-v5.svg", blur: blob },
      {
        box: { t: -0.56, r: -1.31, b: -0.05, l: 51.04 },
        mask: "/intro/masks/s7-g7.svg",
        blur: stripe,
      },
    ],
  },
];

export function IntroScenes() {
  return (
    <div className="flex w-full flex-col">
      {scenes.map((scene) => (
        <IntroScene
          key={scene.id}
          label={scene.label}
          overlays={scene.overlays}
          lettering={scene.lettering}
        />
      ))}
    </div>
  );
}
