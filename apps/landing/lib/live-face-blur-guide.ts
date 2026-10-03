import type { Locale } from "@/lib/locales";

type Guide = {
  title: string; description: string; heading: string; intro: string;
  sections: { heading: string; paragraphs: string[]; steps?: string[] }[];
  demo: string; support: string;
};
export const liveFaceBlurGuide: Record<Locale, Guide> = {
  ko: {
    title: "라이브 블러·방송 모자이크 안내 | InnoLive",
    description: "라이브 모자이크와 블러의 차이, 방송 중 얼굴을 가리는 방법을 안내합니다. InnoLive는 등록한 출연자 외 얼굴에 실시간 블러를 적용합니다.",
    heading: "라이브 블러·방송 모자이크로 얼굴 가리기",
    intro: "야외 라이브 방송에서는 출연자 뒤로 행인이 지나가거나 여러 얼굴이 화면에 들어올 수 있습니다. InnoLive 이노라이브는 방송에 공개할 얼굴을 미리 등록하고, 등록되지 않은 얼굴에 AI로 실시간 블러를 적용하는 방송 지원 서비스입니다.",
    sections: [
      { heading: "라이브 모자이크와 실시간 블러의 차이", paragraphs: ["라이브 모자이크와 라이브 블러는 방송 중 얼굴을 가리기 위해 찾는 기능입니다. 모자이크는 얼굴 영역을 픽셀 블록으로 나누고, 블러는 세부 형태를 흐리게 만듭니다.", "InnoLive의 현재 얼굴 비식별화 효과는 블러 방식입니다. 픽셀 모자이크 효과를 선택하는 기능을 제공한다는 뜻은 아닙니다. 웹 체험에서 실제 처리된 영상을 확인할 수 있습니다."] },
      { heading: "방송 모자이크와 녹화 영상 편집의 차이", paragraphs: ["녹화 영상은 촬영을 마친 뒤 얼굴을 가리고 결과를 확인한 후 게시할 수 있습니다. 라이브 방송은 시청자가 영상을 보는 동안 얼굴을 가려야 합니다.", "InnoLive는 들어오는 영상에서 얼굴을 감지하고 공개 대상자로 등록한 얼굴은 남겨 둡니다. 그 밖의 얼굴은 실시간으로 흐리게 처리해 방송으로 전달합니다. 여러 출연자가 함께 방송할 때는 공개할 사람의 얼굴을 각각 등록할 수 있습니다."] },
      { heading: "InnoLive로 실시간 방송 블러를 준비하는 방법", paragraphs: ["방송에 출연할 사람의 동의를 받고 공개할 얼굴을 등록하세요. 앱과 버전에 따라 제공하는 연결 플랫폼과 화면은 다를 수 있습니다."], steps: ["공개 대상자로 등록할 얼굴이 밝은 곳에서 충분히 크게 보이도록 준비합니다.", "얼굴 등록 목록을 확인하고 방송 플랫폼의 연결과 방송 설정을 완료합니다.", "서버에서 처리한 영상에서 등록한 얼굴과 가려야 할 얼굴의 결과를 확인합니다.", "처리 결과를 확인한 뒤 방송을 시작합니다. 가려야 할 얼굴이 노출되면 방송을 중단하고 고객지원에 문의합니다."] },
      { heading: "웹에서 얼굴 비식별화 체험하기", paragraphs: ["웹 체험은 카메라로 얼굴 블러 결과를 확인하는 기능입니다. 카메라 원본 미리보기와 서버에서 처리한 영상을 구분해 확인하세요. 원본 미리보기에는 블러가 적용되지 않을 수 있습니다.", "체험을 마치면 체험 종료를 눌러 카메라와 연결을 종료하세요. 얼굴 블러는 음성, 복장이나 배경까지 가리는 기능을 의미하지 않습니다."] },
    ], demo: "실시간 블러 체험하기", support: "얼굴 등록·방송 연결 도움말",
  },
  en: {
    title: "Live face blur and mosaic guide | InnoLive",
    description: "Learn how live mosaic differs from blur. InnoLive blurs unregistered faces while keeping registered participants visible during a live stream.",
    heading: "Live face blur and mosaic: how to obscure faces",
    intro: "Bystanders can enter the frame during an outdoor live stream. InnoLive lets you register participants who should remain visible and uses AI to blur other faces in real time.",
    sections: [
      { heading: "Live mosaic and real-time blur", paragraphs: ["Mosaic divides a face into pixel blocks; blur softens facial details. Both terms are used when looking for ways to obscure faces during a live stream.", "InnoLive currently uses blur for face de-identification. It does not offer a selectable pixel mosaic effect. The web demo shows the actual processed video."] },
      { heading: "Live processing and recorded video editing", paragraphs: ["A recorded video can be edited and reviewed before publication. A live stream needs faces obscured while viewers are watching.", "InnoLive detects faces in incoming video, keeps registered participants visible and blurs other faces before sending the processed video to the broadcast. Each participant can register their face."] },
      { heading: "Prepare face blur for your broadcast", paragraphs: ["Get participants' consent before registering faces. Available platforms and screens depend on the app and version."], steps: ["Prepare a well-lit, sufficiently large view of each face to register.", "Check the registered face list, connect your broadcast platform and complete broadcast settings.", "Review registered and unregistered faces in the server-processed video.", "Start broadcasting after checking the result. Stop and contact support if a face that should be obscured is visible."] },
      { heading: "Try face de-identification in your browser", paragraphs: ["The web demo uses your camera to show face blur. Distinguish the original camera preview from the server-processed video; the original preview may not have blur.", "Select End demo when finished to close the camera and connection. Face blur does not obscure voices, clothing or the background."] },
    ], demo: "Try real-time face blur", support: "Face registration and streaming help",
  },
  ja: {
    title: "ライブ配信の顔ぼかし・モザイク解説 | InnoLive",
    description: "ライブ配信のモザイクとぼかしの違い、顔を保護する準備を解説します。InnoLiveは登録した出演者の顔を表示し、他の顔をリアルタイムでぼかします。",
    heading: "ライブ配信の顔ぼかしとモザイクの使い方",
    intro: "屋外のライブ配信では、通行人が画面に入ることがあります。InnoLiveは公開する出演者の顔を事前に登録し、未登録の顔をAIでリアルタイムにぼかす配信支援サービスです。",
    sections: [
      { heading: "モザイクとリアルタイムぼかしの違い", paragraphs: ["モザイクは顔をピクセルのブロックに分け、ぼかしは顔の細部を曖昧にします。どちらも配信中の顔を隠す方法を探す際に使われる言葉です。", "InnoLiveの現在の顔非識別化はぼかし方式です。ピクセルモザイクを選択する機能ではありません。Web体験で実際の処理結果を確認できます。"] },
      { heading: "ライブ配信と録画編集の違い", paragraphs: ["録画は撮影後に顔を隠し、結果を確認してから公開できます。ライブ配信では視聴者が映像を見る間に顔を隠す必要があります。", "InnoLiveは入力映像から顔を検出し、登録した出演者の顔を表示したまま他の顔をぼかして配信に送ります。複数の出演者はそれぞれ顔を登録できます。"] },
      { heading: "顔ぼかしを使って配信を準備する手順", paragraphs: ["出演者の同意を得てから公開する顔を登録してください。対応プラットフォームや画面はアプリとバージョンによって異なります。"], steps: ["明るい場所で、登録する顔が十分に大きく映るように準備します。", "登録顔の一覧を確認し、配信プラットフォームを接続して配信設定を完了します。", "サーバーで処理された映像で、登録した顔と隠す顔の結果を確認します。", "結果を確認してから配信を開始します。隠すべき顔が見える場合は配信を停止し、サポートにお問い合わせください。"] },
      { heading: "ブラウザで顔非識別化を体験", paragraphs: ["Web体験はカメラで顔ぼかしを確認する機能です。元のカメラプレビューとサーバー処理映像を区別してください。元のプレビューにはぼかしが適用されない場合があります。", "終了時は体験終了を選択してカメラと接続を終了してください。顔ぼかしは音声、服装、背景を隠す機能ではありません。"] },
    ], demo: "リアルタイム顔ぼかしを体験", support: "顔登録・配信接続のヘルプ",
  },
};
