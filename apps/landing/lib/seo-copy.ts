import type { Locale } from "@/lib/locales";

type SearchCopy = {
  home: { title: string; description: string };
  pricing: { title: string; description: string };
  tryOut: { title: string; description: string };
};

export const seoCopy: Record<Locale, SearchCopy> = {
  ko: {
    home: {
      title: "라이브 방송 실시간 자동 모자이크 | InnoLive",
      description: "유튜브와 치지직(CHZZK) 라이브 방송에서 행인 얼굴을 실시간으로 감지하고 블러로 가리세요. InnoLive에서 공개할 얼굴을 등록하면 출연자의 얼굴은 보여 줍니다.",
    },
    pricing: {
      title: "실시간 방송 블러·비식별화 요금제 | InnoLive",
      description: "라이브 방송 얼굴 비식별화 요금제를 비교하세요. 무료 웹 체험과 방송 시간·얼굴 등록 수에 맞는 InnoLive 플랜을 확인하세요.",
    },
    tryOut: {
      title: "실시간 블러·얼굴 비식별화 체험 | InnoLive",
      description: "카메라로 InnoLive의 실시간 블러를 체험하세요. 등록한 출연자는 보여주고 다른 얼굴을 가리는 라이브 방송 비식별화를 확인하세요.",
    },
  },
  en: {
    home: {
      title: "InnoLive | Real-time face blur for live streaming",
      description: "Blur unregistered faces in your live stream with InnoLive's real-time AI de-identification. Keep registered participants visible and try face privacy protection in your browser.",
    },
    pricing: {
      title: "Live streaming face blur plans | InnoLive",
      description: "Compare InnoLive plans for real-time face de-identification. Explore the free web demo and plans for your streaming time and registered participants.",
    },
    tryOut: {
      title: "Try real-time face blur and de-identification | InnoLive",
      description: "Try InnoLive's real-time face blur with your camera. See how AI de-identification keeps registered participants visible and blurs other faces for live streaming.",
    },
  },
  ja: {
    home: {
      title: "InnoLive | ライブ配信のリアルタイム顔ぼかし・AI非識別化",
      description: "InnoLiveでライブ配信中の未登録の顔をリアルタイムでぼかしてください。登録した出演者の顔を表示したまま、顔を保護する機能をブラウザで体験できます。",
    },
    pricing: {
      title: "ライブ配信の顔ぼかし・非識別化料金プラン | InnoLive",
      description: "リアルタイム顔非識別化のInnoLive料金プランを比較できます。無料のWeb体験から、配信時間や顔の登録人数に合ったプランをご確認ください。",
    },
    tryOut: {
      title: "リアルタイム顔ぼかし・非識別化を体験 | InnoLive",
      description: "カメラでInnoLiveのリアルタイム顔ぼかしを体験できます。登録した出演者の顔を表示し、他の人の顔をぼかすAI非識別化をご確認ください。",
    },
  },
};
