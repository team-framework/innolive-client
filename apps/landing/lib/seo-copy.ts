import type { Locale } from "@/lib/locales";

type SearchCopy = {
  home: { title: string; description: string };
  pricing: { title: string; description: string };
  tryOut: { title: string; description: string };
  faq: { question: string; answer: string }[];
};

export const seoCopy: Record<Locale, SearchCopy> = {
  ko: {
    home: {
      title: "InnoLive 이노라이브 | 라이브 블러·실시간 비식별화",
      description: "라이브 방송에서 등록한 출연자는 보여주고 다른 얼굴은 실시간 블러로 가립니다. InnoLive 이노라이브의 AI 비식별화를 웹에서 체험하세요.",
    },
    pricing: {
      title: "실시간 방송 블러·비식별화 요금제 | InnoLive",
      description: "라이브 방송 얼굴 비식별화 요금제를 비교하세요. 무료 웹 체험과 방송 시간·얼굴 등록 수에 맞는 InnoLive 플랜을 확인하세요.",
    },
    tryOut: {
      title: "실시간 블러·얼굴 비식별화 체험 | InnoLive",
      description: "카메라로 InnoLive의 실시간 블러를 체험하세요. 등록한 출연자는 보여주고 다른 얼굴을 가리는 라이브 방송 비식별화를 확인하세요.",
    },
    faq: [
      {
        question: "Q. 실시간 방송 블러는 어떻게 적용되나요?",
        answer: "InnoLive는 라이브 방송 화면에서 등록되지 않은 사람의 얼굴을 AI로 찾아 실시간 블러로 가립니다. 공개 대상자로 등록한 얼굴은 남겨 두고, 비식별화를 적용한 영상을 방송으로 보냅니다.",
      },
      {
        question: "Q. 비식별화란 무엇인가요?",
        answer: "비식별화는 영상 속 사람을 알아보기 어렵게 얼굴을 가리는 처리입니다. InnoLive는 야외 라이브 방송에 등장하는 행인의 얼굴을 블러 처리하며, 방송에 출연할 사람의 얼굴은 공개 대상자로 등록할 수 있습니다.",
      },
      {
        question: "Q. 실시간 모자이크와 라이브 방송 모자이크는 블러와 어떻게 다른가요?",
        answer: "모자이크는 얼굴 영역을 픽셀 블록으로 나누고, 블러는 얼굴을 흐리게 만듭니다. InnoLive의 현재 얼굴 비식별화는 실시간 블러 방식입니다. 웹 체험에서 얼굴을 가리는 결과를 확인할 수 있습니다.",
      },
    ],
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
    faq: [
      {
        question: "Q. How does real-time face blur work during a live stream?",
        answer: "InnoLive uses AI to detect and blur unregistered faces in live video. Registered participants remain visible, and the processed video is sent to the broadcast.",
      },
      {
        question: "Q. What is face de-identification?",
        answer: "Face de-identification obscures a person's face to make them harder to recognize. InnoLive blurs bystanders in outdoor live streams, while participants can register their faces to remain visible.",
      },
      {
        question: "Q. How does live stream mosaic differ from face blur?",
        answer: "Mosaic divides a face into pixel blocks, while blur softens facial details. InnoLive currently uses real-time blur for face de-identification. You can see the result in the web demo.",
      },
    ],
  },
  ja: {
    home: {
      title: "InnoLive | ライブ配信のリアルタイム顔ぼかし・AI非識別化",
      description: "InnoLiveはライブ配信中の未登録の顔をリアルタイムでぼかすAI非識別化サービスです。登録した出演者の顔を表示したまま、顔を保護する機能をブラウザで体験できます。",
    },
    pricing: {
      title: "ライブ配信の顔ぼかし・非識別化料金プラン | InnoLive",
      description: "リアルタイム顔非識別化のInnoLive料金プランを比較できます。無料のWeb体験から、配信時間や顔の登録人数に合ったプランをご確認ください。",
    },
    tryOut: {
      title: "リアルタイム顔ぼかし・非識別化を体験 | InnoLive",
      description: "カメラでInnoLiveのリアルタイム顔ぼかしを体験できます。登録した出演者の顔を表示し、他の人の顔をぼかすAI非識別化をご確認ください。",
    },
    faq: [
      {
        question: "Q. ライブ配信のリアルタイム顔ぼかしはどのように動作しますか？",
        answer: "InnoLiveはライブ映像の未登録の顔をAIで検出してぼかします。登録した出演者の顔を表示したまま、非識別化を適用した映像を配信します。",
      },
      {
        question: "Q. 顔の非識別化とは何ですか？",
        answer: "顔の非識別化は、顔を隠して人物を認識しにくくする処理です。InnoLiveは屋外のライブ配信に映る通行人の顔をぼかし、出演者は顔を登録して表示できます。",
      },
      {
        question: "Q. ライブ配信のモザイクと顔ぼかしはどう違いますか？",
        answer: "モザイクは顔をピクセルのブロックに分け、ぼかしは顔の細部を曖昧にします。InnoLiveの現在の顔非識別化はリアルタイムぼかし方式です。Web体験で結果をご確認いただけます。",
      },
    ],
  },
};
