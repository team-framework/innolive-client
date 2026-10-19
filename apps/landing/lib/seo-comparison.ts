import type { SeoGuide } from "@/lib/seo-guides";

const sources = [
  { name: "PRISM Lens FAQ", url: "https://prismlive.com/en_us/faq/faq.html" },
  { name: "PRISM Lens", url: "https://prismlive.com/en_us/lens.html" },
  { name: "PRISM Basic / Plus", url: "https://prismlive.com/en_us/plus.html" },
  { name: "DualMoza · Google Play", url: "https://play.google.com/store/apps/details?id=com.dualmoza.app" },
  { name: "DualMoza · App Store", url: "https://apps.apple.com/kr/app/dualmoza/id6756903535" },
  { name: "InnoLive · App Store", url: "https://apps.apple.com/kr/app/innolive/id6810674918" },
];
const path = "/compare/live-face-blur-apps";
const checkedAt = "2026-10-17";
const related = ["/mosaic-software", "/mobile-live-face-blur", "/face-mosaic", "/free-face-blur"];

export const koreanComparisonGuide: SeoGuide = {
  path,
  title: "실시간 방송 모자이크 앱 비교: PRISM Lens·DualMoza·InnoLive",
  description: "PC와 모바일, 얼굴 공개 기준, 송출 방식으로 PRISM Lens·DualMoza·InnoLive를 비교하세요. 공식 설명과 설치 페이지를 함께 제공합니다.",
  heading: "실시간 방송 모자이크 앱 비교",
  intro: "PRISM Lens는 PC의 가상 카메라로 방송 프로그램에 연결합니다. DualMoza와 InnoLive는 모바일 방송 앱입니다. InnoLive에서는 공개할 출연자를 얼굴로 등록하고, 미등록 얼굴을 블러로 가려 유튜브·치지직으로 송출합니다. 사용할 기기와 얼굴을 공개하는 기준부터 비교하세요.",
  comparison: {
    checkedAt,
    disclosure: "InnoLive 팀이 각 제품의 공식 설명을 확인해 작성했습니다. 경쟁 제품을 직접 설치해 성능을 비교한 결과는 아닙니다. 기능·가격은 버전과 스토어에 따라 달라질 수 있습니다.",
    caption: "PRISM Lens·DualMoza·InnoLive의 공식 설명 기준 기능 비교",
    columns: ["비교 기준", "PRISM Lens", "DualMoza", "InnoLive"],
    rows: [
      { label: "사용 기기", cells: ["Windows·macOS PC", "Android·iOS 모바일", "Android 11+·iOS 18+ 모바일"] },
      { label: "얼굴 공개 기준", cells: ["전체 얼굴 또는 배경 얼굴에 효과 적용", "전체·가장 가까운 사람·그 사람을 제외한 대상 선택", "공개할 얼굴을 등록하고 미등록 얼굴에 블러 적용"] },
      { label: "가리는 방식", cells: ["픽셀·블러·스티커", "모자이크·블러, 얼굴 또는 인물", "얼굴 블러"] },
      { label: "방송 연결", cells: ["가상 카메라를 방송 프로그램의 영상 소스로 선택", "앱에서 YouTube·Twitch·RTMP로 송출", "앱에서 YouTube·CHZZK로 송출"] },
      { label: "이용 조건 확인", cells: ["Basic·Plus별 효과와 제한 확인", "스토어 설명상 라이브 방송은 Pro 필요", "무료 Spark와 요금제별 방송 시간·기능 한도 확인"] },
    ],
    sources,
  },
  sections: [
    { heading: "PC 방송과 스마트폰 야외 방송", paragraphs: ["PC에서 여러 영상 소스를 구성한다면 가상 카메라 연결 방식을 확인하세요. 스마트폰을 들고 이동하며 방송한다면 앱에서 얼굴을 가린 영상을 직접 송출하는 흐름을 확인하세요."] },
    { heading: "같이 출연하는 사람을 계속 공개하려면", paragraphs: ["InnoLive는 사전에 등록한 얼굴을 공개하는 방식을 사용합니다. 본인과 함께 출연하는 사람을 등록하고, 다른 사람이 들어오거나 위치가 바뀌는 장면에서 결과를 확인하세요. 얼굴의 각도·크기·가림·조명은 감지에 영향을 줄 수 있습니다."] },
    { heading: "설치와 방송 조건을 확인하세요", paragraphs: ["InnoLive의 제품 안내에서 Android·iOS 설치 링크를 확인할 수 있습니다. 웹 체험으로 얼굴 블러 결과를 살펴본 뒤 앱에서 계정을 연결하고 방송 설정을 저장하세요. 무료 이용 범위와 남은 방송 시간은 요금제에서 확인하세요."] },
  ],
  faq: [
    { question: "PC 없이 실시간 얼굴 모자이크 방송을 할 수 있나요?", answer: "모바일 앱의 직접 송출 기능을 확인하세요. InnoLive는 Android·iOS 앱에서 얼굴 블러가 적용된 영상을 유튜브·치지직으로 송출합니다. 인터넷 연결과 방송 계정 또는 스트림 키, 사용 가능한 방송 시간이 필요합니다." },
    { question: "InnoLive는 OBS의 가상 카메라로 사용하나요?", answer: "이 안내에서 다루는 InnoLive의 공개 사용 흐름은 모바일 앱에서 유튜브·치지직으로 송출하는 방식입니다. OBS용 가상 카메라 설치나 연결 기능은 이 안내의 지원 범위에 포함하지 않습니다." },
    { question: "얼굴을 가리는 성능도 비교했나요?", answer: "이 표는 공식 기능 설명을 비교했습니다. 같은 기기·장면·네트워크에서 감지율이나 지연을 측정한 성능 비교는 하지 않았습니다. 실제 방송 환경에서 얼굴이 작아지거나 가려지는 장면까지 확인하세요." },
  ],
  related,
};

export const englishComparisonGuide: SeoGuide = {
  path,
  title: "Live Face Blur Apps Compared: PRISM Lens, DualMoza & InnoLive",
  description: "Compare devices, face selection and streaming workflows for PRISM Lens, DualMoza and InnoLive, with official sources and installation information.",
  heading: "Choosing a live face blur app",
  intro: "PRISM Lens connects to desktop broadcasting software as a virtual camera. DualMoza and InnoLive are mobile broadcasting apps. InnoLive keeps registered guests visible, blurs unregistered faces and streams to YouTube or CHZZK. Compare the device and face selection workflow you need.",
  comparison: {
    checkedAt,
    disclosure: "The InnoLive team prepared this comparison from official product descriptions. We have not benchmarked competing apps. Features and prices can vary by version and store.",
    caption: "PRISM Lens, DualMoza and InnoLive: official feature descriptions",
    columns: ["Criterion", "PRISM Lens", "DualMoza", "InnoLive"],
    rows: [
      { label: "Device", cells: ["Windows or macOS PC", "Android or iOS mobile device", "Android 11+ or iOS 18+ mobile device"] },
      { label: "Face selection", cells: ["Apply effects to all faces or background faces", "Cover everyone, the closest person, or everyone else", "Register guests to keep visible; blur unregistered faces"] },
      { label: "Effect", cells: ["Pixelation, blur or stickers", "Mosaic or blur on faces or people", "Face blur"] },
      { label: "Broadcast workflow", cells: ["Select the virtual camera in broadcasting software", "Stream from the app to YouTube, Twitch or RTMP", "Stream from the app to YouTube or CHZZK"] },
      { label: "Access conditions", cells: ["Check Basic and Plus effects and limits", "Store descriptions require Pro for live streaming", "Check free Spark and each plan's time and feature limits"] },
    ],
    sources,
  },
  sections: [
    { heading: "Desktop production or mobile outdoor streaming", paragraphs: ["For a desktop production with multiple sources, check how the virtual camera connects. For a handheld outdoor broadcast, check whether the mobile app sends processed video directly to your streaming platform."] },
    { heading: "Keep recurring guests visible", paragraphs: ["InnoLive identifies the faces you register to keep visible. Register yourself and your guests, then check scenes where other people enter or change position. Face angle, size, occlusion and lighting can affect detection."] },
    { heading: "Check installation and streaming conditions", paragraphs: ["The InnoLive product guide links to the Android and iOS apps. Preview face blur in the web demo, then connect your account and save broadcast settings in the app. Check pricing for free usage and available streaming time."] },
  ],
  faq: [
    { question: "Can I stream with face blur without a PC?", answer: "Look for a mobile app with direct broadcasting. InnoLive streams processed camera video from Android and iOS to YouTube or CHZZK. You need internet access, a streaming account or stream key, and available streaming time." },
    { question: "Does this guide describe InnoLive as an OBS virtual camera?", answer: "The public InnoLive workflow covered here is direct broadcasting from the mobile app to YouTube or CHZZK. OBS virtual camera installation and integration are outside the supported scope of this guide." },
    { question: "Have you compared face detection performance?", answer: "This table compares official feature descriptions. We have not measured detection accuracy or latency across the apps on matched devices, scenes and networks. Test small and partially hidden faces in your own streaming environment." },
  ],
  related,
};

export const japaneseComparisonGuide: SeoGuide = {
  path,
  title: "ライブ顔ぼかしアプリ比較：PRISM Lens・DualMoza・InnoLive",
  description: "PRISM Lens・DualMoza・InnoLiveの対応端末、顔の選択方法、配信手順を比較できます。公式情報とインストール先を掲載しています。",
  heading: "ライブ配信用の顔ぼかしアプリを選ぶ",
  intro: "PRISM LensはPCの仮想カメラとして配信ソフトに接続します。DualMozaとInnoLiveはモバイル配信アプリです。InnoLiveは登録した出演者を表示し、未登録の顔をぼかしてYouTubeやCHZZKへ配信します。使用する端末と、表示する顔の選び方を比較してください。",
  comparison: {
    checkedAt,
    disclosure: "InnoLiveチームが各製品の公式説明を確認して作成しました。競合アプリの性能測定は実施していません。機能や料金はバージョンやストアによって異なる場合があります。",
    caption: "PRISM Lens・DualMoza・InnoLiveの公式説明に基づく機能比較",
    columns: ["比較項目", "PRISM Lens", "DualMoza", "InnoLive"],
    rows: [
      { label: "端末", cells: ["Windows・macOSのPC", "Android・iOSのモバイル端末", "Android 11以降・iOS 18以降のモバイル端末"] },
      { label: "顔の選択", cells: ["すべての顔または背景の顔に効果を適用", "全員、最も近い人、その人以外を選択", "表示する出演者を登録し、未登録の顔をぼかす"] },
      { label: "処理方式", cells: ["ピクセル化、ぼかし、ステッカー", "顔または人物へのモザイク・ぼかし", "顔のぼかし"] },
      { label: "配信手順", cells: ["配信ソフトで仮想カメラを映像ソースに選択", "アプリからYouTube・Twitch・RTMPへ送信", "アプリからYouTube・CHZZKへ送信"] },
      { label: "利用条件", cells: ["Basic・Plusの効果と制限を確認", "ストアの説明ではライブ配信にProが必要", "無料Sparkと各プランの時間・機能の上限を確認"] },
    ],
    sources,
  },
  sections: [
    { heading: "PC配信とスマートフォンの屋外配信", paragraphs: ["PCで複数の映像ソースを使う場合は、仮想カメラの接続方法を確認してください。スマートフォンを持って移動しながら配信する場合は、アプリから処理済み映像を配信先へ直接送れるか確認してください。"] },
    { heading: "一緒に出演する人を表示し続ける", paragraphs: ["InnoLiveは表示する対象として登録した顔を識別します。自分と出演者を登録し、他の人が入ったり位置が変わったりする場面で確認してください。顔の角度、大きさ、遮蔽物、照明は検出に影響します。"] },
    { heading: "インストールと配信条件を確認する", paragraphs: ["InnoLiveの製品ガイドにAndroid・iOSアプリへのリンクがあります。Web体験でぼかしを確認し、アプリでアカウントを連携して設定を保存してください。無料の利用範囲や配信可能時間は料金ページをご覧ください。"] },
  ],
  faq: [
    { question: "PCなしで顔をぼかして配信できますか？", answer: "直接配信に対応したモバイルアプリをご確認ください。InnoLiveはAndroid・iOSから処理済みカメラ映像をYouTubeやCHZZKへ送信します。インターネット接続、配信アカウントまたはストリームキー、利用可能な配信時間が必要です。" },
    { question: "このガイドはInnoLiveをOBSの仮想カメラとして紹介していますか？", answer: "このガイドではモバイルアプリからYouTube・CHZZKへ直接配信する手順を紹介しています。OBS用の仮想カメラのインストールや連携は、このガイドの対応範囲に含まれません。" },
    { question: "顔の検出性能も比較していますか？", answer: "この表は公式の機能説明を比較しています。同じ端末、場面、ネットワークでの検出精度や遅延の測定は実施していません。実際の配信環境で、小さく映る顔や一部が隠れた顔も確認してください。" },
  ],
  related,
};
