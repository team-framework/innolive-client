import type { Locale } from "@/lib/locales";

export const aboutCopy = {
  ko: {
    title: "InnoLive 소개 | Framework의 실시간 얼굴 블러 방송 앱",
    description: "InnoLive는 Framework 팀의 라이브 방송 앱입니다. 등록한 출연자는 보여 주고 미등록 얼굴은 실시간 블러로 가려 유튜브·치지직으로 송출합니다.",
    heading: "InnoLive는 어떤 서비스인가요?",
    intro: "InnoLive는 Framework 팀이 만드는 라이브 방송 앱입니다. 공개할 출연자의 얼굴을 등록하면, 카메라에 들어오는 미등록 얼굴을 실시간 블러로 가려 유튜브·치지직(CHZZK)으로 송출합니다.",
    factsTitle: "제품 정보", name: "제품명", team: "운영팀", website: "공식 웹사이트",
    platforms: "방송 앱", trial: "웹 체험", trialValue: "카메라로 얼굴 감지·블러 결과 확인",
    limitsTitle: "사용 범위", limits: "웹 체험은 얼굴 처리 결과를 확인하는 용도입니다. 실제 송출은 방송 앱에서 준비합니다. 얼굴의 크기·각도·가림·조명에 따라 감지 결과가 달라질 수 있으므로 송출 전에 확인하세요.",
    sourcesTitle: "사용 방법과 공식 채널", guide: "얼굴 등록과 실시간 블러 안내", pricing: "요금제와 이용 한도", support: "운영팀과 고객지원", sourceNote: "제품 기능과 이용 조건은 각 안내 페이지에서 확인할 수 있습니다. 문의는 고객지원의 연락처를 이용하세요.",
  },
  en: {
    title: "About InnoLive | Framework's live face blur app",
    description: "InnoLive is a live streaming app by Framework. Keep registered guests visible, blur unregistered faces and stream to YouTube or CHZZK.",
    heading: "What is InnoLive?",
    intro: "InnoLive is a live streaming app built by the Framework team. Register the guests you want to show, blur unregistered faces in the camera feed, and stream the processed video to YouTube or CHZZK.",
    factsTitle: "Product facts", name: "Product", team: "Operations team", website: "Official website",
    platforms: "Streaming apps", trial: "Web trial", trialValue: "Check face detection and blur with your camera",
    limitsTitle: "Scope of use", limits: "The web trial lets you check face processing. Prepare actual broadcasts in the streaming app. Face size, angle, occlusion and lighting can affect detection, so check the result before going live.",
    sourcesTitle: "Guides and official channels", guide: "Face registration and live blur guide", pricing: "Plans and usage limits", support: "Operations team and support", sourceNote: "Check the linked guides for product features and usage conditions. Use the contact details on the support page for questions.",
  },
  ja: {
    title: "InnoLiveについて | Frameworkのリアルタイム顔ぼかし配信アプリ",
    description: "InnoLiveはFrameworkチームのライブ配信アプリです。登録した出演者を表示し、未登録の顔をリアルタイムでぼかしてYouTube・CHZZKへ配信します。",
    heading: "InnoLiveとは？",
    intro: "InnoLiveはFrameworkチームが開発するライブ配信アプリです。公開する出演者の顔を登録し、カメラに映る未登録の顔をリアルタイムでぼかして、YouTube・CHZZKへ配信します。",
    factsTitle: "製品情報", name: "製品名", team: "運営チーム", website: "公式サイト",
    platforms: "配信アプリ", trial: "Web体験", trialValue: "カメラで顔検出とぼかしの結果を確認",
    limitsTitle: "利用範囲", limits: "Web体験では顔処理の結果を確認できます。実際の配信は配信アプリで準備してください。顔の大きさ・角度・遮蔽・照明によって検出結果が変わるため、配信前に確認してください。",
    sourcesTitle: "ガイドと公式チャンネル", guide: "顔登録とリアルタイムぼかしのガイド", pricing: "料金プランと利用上限", support: "運営チームとサポート", sourceNote: "製品の機能と利用条件は各ガイドで確認できます。お問い合わせにはサポートページの連絡先をご利用ください。",
  },
} satisfies Record<Locale, Record<string, string>>;
