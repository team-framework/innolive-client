export type PrivacySlide = {
  src: string;
  alt: string;
  label: string;
};

// Replace src with a local image or GIF path when the media is ready.
export const privacySlides: PrivacySlide[] = [
  { src: "", alt: "행인 얼굴 감지 데모", label: "행인 얼굴 감지 데모" },
  { src: "", alt: "실시간 비식별화 데모", label: "실시간 비식별화 데모" },
  { src: "", alt: "안전한 라이브 송출 데모", label: "안전한 라이브 송출 데모" },
];
