export type PlanFeature = {
  icon: "check" | "sparkles";
  text: string;
};

export type Plan = {
  id: string;
  name: string;
  nameTone: "plain" | "streamer";
  description: string;
  originalPrice?: {
    amount: string;
    period: string;
  };
  price: {
    amount: string;
    period: string;
    emphasize?: boolean;
  };
  cta: {
    label: string;
    href?: string;
    current?: boolean;
  };
  features: PlanFeature[];
  ribbon?: string;
  footnotes?: string[];
  helpLabel?: string;
};

export const personalPlans: Plan[] = [
  {
    id: "free",
    name: "Free",
    nameTone: "plain",
    description: "보호에 필요한 필수 기능",
    price: { amount: "0", period: "월" },
    cta: { label: "현재 플랜", current: true },
    features: [
      { icon: "check", text: "모든 행인을 정확하게 비식별화" },
      { icon: "check", text: "상위 모델 제한적 사용" },
      { icon: "check", text: "X시간 연속 방송" },
      { icon: "check", text: "최대 2명 얼굴 등록" },
      { icon: "check", text: "Android, iOS 어플리케이션" },
    ],
  },
  {
    id: "streamer",
    name: "Streamer",
    nameTone: "streamer",
    description: "Segmentation 및 더 많은 사용량",
    originalPrice: { amount: "18,000", period: "월" },
    price: { amount: "0", period: "월", emphasize: true },
    cta: { label: "Streamer 요금제 구독", href: "/signup" },
    features: [
      { icon: "sparkles", text: "무료 플랜의 모든 기능" },
      { icon: "check", text: "Segmentation 기반의 향상된 비식별화" },
      { icon: "check", text: "2배 사용량 제공" },
      { icon: "check", text: "XX시간 연속 방송" },
      { icon: "check", text: "최대 5명 얼굴 등록" },
    ],
    ribbon: "베타 테스터",
  },
  {
    id: "pro",
    name: "Pro",
    nameTone: "plain",
    description: "얼굴 대체 및 최고 성능",
    originalPrice: { amount: "34,000", period: "월" },
    price: { amount: "0", period: "월", emphasize: true },
    cta: { label: "Pro 요금제 구독", href: "/signup" },
    features: [
      { icon: "sparkles", text: "Streamer 플랜의 모든 기능" },
      { icon: "check", text: "실시간 얼굴 대체로 더욱 자연스러운 방송" },
      { icon: "check", text: "3배 사용량 제공" },
      { icon: "check", text: "실험적 모델 접근" },
    ],
    ribbon: "베타 테스터",
  },
  {
    id: "on-device",
    name: "On Device",
    nameTone: "plain",
    description: "무제한 사용량, 극한의 보안성",
    originalPrice: { amount: "120,000", period: "일회성 결제" },
    price: { amount: "0", period: "일회성 결제", emphasize: true },
    cta: { label: "On Device 결제", href: "/signup" },
    features: [
      { icon: "check", text: "모든 행인을 정확하게 비식별화" },
      { icon: "check", text: "한 번 결제로 영구적 사용" },
      { icon: "check", text: "모든 한도 해제" },
      { icon: "check", text: "Android, iOS 어플리케이션" },
      { icon: "check", text: "기기에서 비식별화 후 송출" },
      { icon: "check", text: "무지연 송출" },
    ],
    ribbon: "베타 테스터",
    footnotes: [
      "*프로모션 종료 시 플랜이 취소될 수 있습니다.",
      "*기기 성능에 따라 비식별화 성능에 편차가 있을 수 있습니다.",
    ],
    helpLabel: "On Device 안내",
  },
];

export const businessPlans: Plan[] = [
  {
    id: "crew",
    name: "Crew",
    nameTone: "plain",
    description: "하나의 플랜으로 저렴하게",
    price: { amount: "30,000 ~ ₩93,500", period: "월" },
    cta: { label: "Crew 요금제 구독", href: "/signup" },
    features: [
      { icon: "check", text: "최대 3계정 공유" },
      { icon: "sparkles", text: "선택한 플랜의 모든 기능" },
    ],
  },
  {
    id: "business",
    name: "Business",
    nameTone: "plain",
    description: "어떤 상황이든 유연하게 맞춤",
    price: { amount: "맞춤 가격", period: "월" },
    cta: { label: "도입 문의", href: "/signup" },
    features: [
      { icon: "check", text: "맞춤형 플랜" },
      { icon: "check", text: "향상된 보안" },
    ],
  },
];
