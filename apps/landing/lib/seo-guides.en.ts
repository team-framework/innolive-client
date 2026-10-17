import type { SeoGuide } from "@/lib/seo-guides";
import { getMessages } from "@/lib/messages";
import { getPersonalPlans } from "@/lib/plans";

const spark = getPersonalPlans(getMessages("en"), "en")[0];
const limits = spark.features.slice(1).map(({ text }) => text).join(", ");

export const englishSeoGuides: SeoGuide[] = [
  {
    path: "/live-mosaic",
    title: "Real-time Face Blur for Live Streaming | InnoLive",
    description: "Blur unregistered faces in real time while keeping registered guests visible. Prepare your YouTube or CHZZK stream with InnoLive.",
    heading: "Real-time face blur for live streams",
    intro: "InnoLive is a real-time face blur application for live streaming. It detects unregistered faces in live camera video and blurs them before broadcast output. Registered guests stay visible. Send the processed video to YouTube or CHZZK. The current effect is blur, not pixelated mosaic.",
    sections: [
      { heading: "1. Register the faces you want to show", paragraphs: ["Register yourself and your guests in the streaming app. InnoLive compares camera faces with those registrations and blurs unregistered faces.", "The blur area updates as detected faces move. Test a frame containing both yourself and other people to check the distinction."] },
      { heading: "2. Check face blur at your filming location", paragraphs: ["Enable face anonymization and inspect nearby, distant, moving and side-facing faces.", "Dim lighting and obstructions can affect detection. If a face you want hidden remains visible, adjust the camera direction and distance, then check again."] },
      { heading: "3. Connect your account and save broadcast settings", paragraphs: ["Connect YouTube or CHZZK and save the broadcast title, visibility and other settings. Prepare the broadcast after saving.", "The web demo previews camera face detection and blur. Use the streaming app to prepare actual platform broadcasts."] },
      { heading: "4. Follow your platform's start procedure", paragraphs: ["For YouTube, check the preparation screen before starting the live broadcast. CHZZK can start broadcasting when the streaming connection begins, so inspect framing and face processing before connecting.", "Camera input → face detection and comparison with registered faces → blur on unregistered faces → processed video sent to the platform. See the platform guides for preparation steps."] },
      { heading: "Check information beyond faces", paragraphs: ["Face de-identification processes faces. Check name tags, addresses, vehicle plates, chat text and conversations separately. Adjust framing to keep information you do not want to share out of the shot.", "Changing face processing settings cannot take back a scene already broadcast. Check the result again when lighting or your filming location changes."] },
    ],
    faq: [
      { question: "How do I apply real-time face blur to a live stream?", answer: "Register the faces you want to show in InnoLive and enable face anonymization. Check that unregistered faces are blurred, then prepare your YouTube or CHZZK stream. InnoLive processes faces before sending video to the platform." },
      { question: "Can I blur everyone except myself?", answer: "Register yourself to keep your face visible while blurring unregistered faces. You can register guests too. Check a scene with several faces before broadcasting." },
      { question: "Does InnoLive support pixelated mosaic?", answer: "InnoLive currently uses blur to soften facial details. Pixelated mosaic is not supported." },
      { question: "Can I try face blur for free?", answer: "The free web demo lets you check face detection and blur with your camera. See pricing for actual broadcast time, face registration limits and streaming conditions." },
      { question: "Can I start a broadcast from the web demo?", answer: "The web demo lets you check face detection and blur. Prepare YouTube and CHZZK broadcasts in the streaming app." },
      { question: "Can I upload a recording to blur faces?", answer: "InnoLive processes faces in live camera video. It does not provide recorded-file uploads or editing." },
    ],
    related: ["/face-mosaic", "/live-privacy", "/mobile-live-face-blur", "/free-face-blur", "/youtube", "/chzzk"],
  },
  {
    path: "/face-mosaic", title: "Automatic Face Blur & De-identification | InnoLive",
    description: "Automatically blur unregistered faces in live video. Register yourself and your guests to keep their faces visible while streaming.",
    heading: "Detect and blur faces automatically",
    intro: "InnoLive detects faces in live video and blurs those you have not registered to stay visible. Registered guests remain visible. The blur area follows the detected face position as it changes.",
    sections: [
      { heading: "Choose whose face stays visible", paragraphs: ["Register the people appearing in your broadcast. InnoLive compares detected faces with those registrations and blurs unregistered faces.", "Check face registration and anonymization settings before starting your stream."] },
      { heading: "Check guests and bystanders together", paragraphs: ["During outdoor streams, other people may pass behind your guests. Check a scene containing both registered and unregistered faces.", "Camera angles, obstructions and lighting can affect detection. Test small faces and side views in the demo and preparation screen."] },
      { heading: "Preview the blur effect", paragraphs: ["Pixelated mosaic divides a face into blocks; blur softens its details. InnoLive currently uses real-time blur. You can inspect the effect in the web demo.", "InnoLive does not offer recorded-file uploads or automatic video editing. See the video guide for the difference between live processing and recorded-video editing."] },
    ],
    faq: [
      { question: "Can I blur everyone except myself on an IRL stream?", answer: "Register your own face to keep it visible while InnoLive blurs unregistered faces. You can register guests too. Before streaming, check a scene with your face and other people's faces together." },
      { question: "Does the blur follow moving faces?", answer: "InnoLive updates the blur area based on detected face positions. Face size, direction, obstructions and lighting affect detection, so check scenes with movement and head turns." },
      { question: "Can VTubers blur bystanders during outdoor streams?", answer: "InnoLive blurs unregistered faces in the camera input. It does not composite VTuber avatars. Check your video input and streaming setup first." },
    ],
    related: ["/live-mosaic", "/mobile-live-face-blur", "/blog/automatic-mosaic", "/mosaic-software"],
  },
  {
    path: "/live-privacy", title: "Real-time Face Blur for Outdoor & IRL Streams | InnoLive",
    description: "Blur unregistered faces before sending outdoor and IRL video to viewers. Register your guests and check the camera preview before streaming.",
    heading: "Automatic face blur for outdoor and IRL live streams",
    intro: "To hide bystanders' faces on an IRL stream, process the video before sending it to viewers. InnoLive keeps registered guests visible and blurs unregistered faces in real time. Check detection and blur at your filming location before going live.",
    sections: [
      { heading: "Check the background as well as your guests", paragraphs: ["Even when the camera points at you, pedestrians or nearby customers may appear behind you. Check both your guests' face settings and the faces in the background.", "Adjust your camera direction to reduce close-up views of people nearby."] },
      { heading: "Process faces before broadcasting", paragraphs: ["You cannot take back a scene viewers have already received. Apply face blur between camera input and platform output.", "Check that face anonymization is enabled in the preparation screen, then send the processed video."] },
      { heading: "Prepare for changing outdoor conditions", paragraphs: ["Register your guests and test under the lighting at your location. As you move, check framing and faces of different sizes and directions.", "Also check for name tags, addresses, vehicle plates and conversations. Face anonymization does not automatically hide other information or audio."] },
    ],
    faq: [{ question: "Does face blur guarantee that filming will not cause a privacy dispute?", answer: "Face anonymization blurs unregistered faces in video. It does not determine consent, assess image rights or guarantee a legal outcome. Check for other identifying information, including name tags, addresses, vehicle plates and conversations." }],
    related: ["/live-mosaic", "/face-mosaic", "/mobile-live-face-blur", "/blog/live-face-blur"],
  },
  {
    path: "/youtube", title: "Real-Time Face Blur for YouTube Live | InnoLive",
    description: "Blur unregistered faces before streaming to YouTube. Connect your account, save broadcast settings and check the preview before going live.",
    heading: "Automatically blur faces on YouTube Live",
    intro: "Send live video with unregistered faces blurred to your connected YouTube account. Register your guests, save your account and broadcast settings, then check the processed video during preparation before starting the live broadcast.",
    sections: [
      { heading: "Connect your YouTube account", paragraphs: ["Connect the account in the streaming app. Check that your channel can broadcast live and register the people whose faces should remain visible.", "Use the web camera demo to inspect face processing. Prepare your YouTube broadcast in the app."] },
      { heading: "Save settings and check the preparation screen", paragraphs: ["Save the broadcast title, visibility and other settings before preparing the stream. YouTube preparation and the action that makes the stream live are separate steps.", "Check that face anonymization is enabled and that guests remain visible while other faces are blurred."] },
      { heading: "Start the live broadcast", paragraphs: ["After checking preparation, select the broadcast start action to make the processed video live. InnoLive applies face blur before sending video to YouTube.", "InnoLive processes live camera video. It does not edit videos already uploaded to YouTube."] },
    ], related: ["/live-mosaic", "/live-privacy", "/chzzk"],
  },
  {
    path: "/chzzk", title: "CHZZK Real-time Automatic Face Blur | InnoLive",
    description: "Blur unregistered faces in CHZZK broadcasts. Connect your account and check face processing before the RTMP streaming connection starts.",
    heading: "Real-time automatic face blur for CHZZK",
    intro: "Send video with unregistered faces blurred to CHZZK. CHZZK starts broadcasting when the RTMP streaming connection begins, so register your guests and check face processing before connecting the stream.",
    sections: [
      { heading: "Prepare your CHZZK account and settings", paragraphs: ["Connect your CHZZK account in the streaming app. Save the title, category and other settings, and check that the channel can broadcast.", "Check the connected channel, broadcast title and streaming information before starting."] },
      { heading: "Check face blur before connecting", paragraphs: ["The streaming connection starts the CHZZK broadcast. It does not provide the same private preparation stage as YouTube, so check camera framing and anonymization before connecting.", "Confirm that registered guests remain visible and other detected faces are blurred in the video you will send."] },
      { heading: "Prepare for outdoor broadcasts", paragraphs: ["Bystanders change as you walk or film an event. Check detection and framing together.", "The current effect is blur. Disabling face anonymization stops face blurring, so check the setting before streaming."] },
    ], related: ["/live-mosaic", "/live-privacy", "/youtube"],
  },
  {
    path: "/mosaic-software", title: "Real-time De-identification Service & App | InnoLive",
    description: "Use a real-time de-identification application to blur unregistered faces on live streams. Keep guests visible and broadcast to YouTube or CHZZK.",
    heading: "A real-time de-identification service for live streaming",
    intro: "InnoLive is a real-time de-identification service with Android and iOS streaming apps. The application automatically blurs unregistered faces while keeping registered guests visible. Try face blur in your browser, then use the app to stream processed video to YouTube or CHZZK.",
    sections: [
      { heading: "How real-time face de-identification works", paragraphs: ["Face de-identification hides facial details that could identify someone. InnoLive detects faces and blurs those you have not registered to keep visible. Register the people you want to show during your broadcast.", "The service processes faces in live camera video. It does not automatically remove names, vehicle plates or audio. The current face effect is blur, not pixelated mosaic."] },
      { heading: "Use the application for live streaming", paragraphs: ["Connect your streaming account, register guests and save broadcast settings. Check anonymization results before starting YouTube or CHZZK output.", "Inspect the video that will reach viewers. Follow the start procedure for your selected platform."] },
      { heading: "Try face processing in your browser", paragraphs: ["Use your camera in the web demo to check face detection and blur, including the result before and after registering a face.", "InnoLive processes live camera video. Recorded-file uploads, batch editing and edited-file exports are not available. See pricing for usage and face registration limits."] },
    ], faq: [
      {
            "question": "Which application can blur faces during a live broadcast?",
            "answer": "InnoLive is a real-time face blur app for Android and iOS. It detects and blurs unregistered faces while keeping registered guests visible, and streams the processed video to YouTube or CHZZK."
      },
      {
            "question": "Where can I install InnoLive?",
            "answer": "Install the Android app from ONE store or the iOS app from the App Store. Android 11 or later, or iOS 18 or later, is required. Use the app installation links on this page to open the store."
      },
      {
            "question": "What information can InnoLive hide in a broadcast?",
            "answer": "InnoLive detects and blurs faces. Register guests to keep their faces visible. Names, vehicle plates and audio are not automatically hidden. Check the processing results before broadcasting."
      }
],
    related: ["/face-mosaic", "/live-mosaic", "/free-face-blur", "/blog/automatic-mosaic"],
  },
  {
    path: "/free-face-blur", title: "Free Live Face Blur and Plan Limits | InnoLive",
    description: "Check the free Spark plan and beta pricing for live face blur. Compare streaming limits and face registration before preparing your broadcast.",
    heading: "Blur faces in live streams for free",
    intro: `The Spark plan costs KRW ${spark.price.amount} per month. InnoLive detects and blurs unregistered faces in live video while keeping registered guests visible. Streaming time and face registration limits depend on your plan.`,
    showPersonalPlans: true,
    sections: [
      { heading: "What the free Spark plan includes", paragraphs: [`Spark currently includes: ${limits}. Check the table and pricing page for the streaming time and number of registered guests you need.`, "Check monthly time, session duration, video quality and supported streaming platforms before broadcasting. The face effect is blur."] },
      { heading: "Beta pricing and regular prices", paragraphs: ["Glow, Beam and Plasma cost KRW 0 per month during beta. Compare beta and regular monthly prices in the table below."] },
      { heading: "Check face blur before streaming", paragraphs: ["Use the web demo to inspect camera face detection and blur. For YouTube or CHZZK broadcasts, connect your account and save broadcast settings in the app.", "Register the faces you want to show and inspect the other faces. Check your plan and remaining usage in the app before broadcasting."] },
    ],
    faq: [
      { question: "Can I use automatic face blur for free?", answer: `Spark costs KRW ${spark.price.amount} per month. It currently includes: ${limits}. InnoLive detects faces and applies real-time blur to unregistered faces.` },
      { question: "Is free face blur unlimited?", answer: "Spark has streaming time limits. Glow, Beam and Plasma also cost KRW 0 per month during beta. Check the pricing page for each plan's time limits, features and prices." },
      { question: "Can I edit recorded videos online for free?", answer: "The web demo lets you check camera face blur. InnoLive does not offer recorded-video uploads, editing or file exports." },
    ], related: ["/live-mosaic", "/mobile-live-face-blur", "/blog/automatic-mosaic"],
  },
  {
    path: "/mobile-live-face-blur", title: "Real-time Face Blur App for Outdoor Streaming | InnoLive",
    description: "Keep your face visible and blur bystanders on Android and iOS live streams. Register guests and prepare YouTube or CHZZK output in the app.",
    heading: "A real-time blur app for outdoor mobile streams",
    intro: "The Android and iOS streaming apps blur unregistered faces in live camera video before sending it to YouTube or CHZZK. Register yourself to keep your face visible. Check the processed video at your filming location before broadcasting.",
    sections: [
      { heading: "1. Register the people you want to show", paragraphs: ["Register your face to keep it visible while blurring others. You can register guests individually. Check your plan for the face registration limit.", "Check a frame containing registered and unregistered faces. Test head turns and camera movement too."] },
      { heading: "2. Connect your account and save settings", paragraphs: ["Connect YouTube or CHZZK in the app. Check your channel, broadcast title and other settings, then save them before preparing the stream.", "The web demo previews camera processing. Use the app for actual broadcasts. Find installation links in the home page download menu."] },
      { heading: "3. Enable anonymization and follow the platform steps", paragraphs: ["For YouTube, prepare the stream before selecting the live start action. For CHZZK, inspect the result before connecting RTMP because that connection starts the broadcast.", "Lighting, movement, network conditions and face sizes change outdoors. Check framing and face processing, and reduce scenes containing information you do not want to share."] },
    ],
    faq: [
      { question: "Can I automatically blur faces on a phone stream?", answer: "The Android and iOS apps blur unregistered faces in camera video in real time. Registered faces stay visible, and you can send the processed video to YouTube or CHZZK." },
      { question: "How do I keep the streamer's face visible?", answer: "Register the streamer's face and enable face anonymization. Check that the preview keeps registered faces visible and blurs unregistered faces before starting." },
      { question: "Is this a feature inside the YouTube or CHZZK app?", answer: "InnoLive processes camera video in its own app and sends it to your connected YouTube or CHZZK account. It does not add face blur controls inside those platforms' apps." },
    ], related: ["/face-mosaic", "/free-face-blur", "/youtube", "/chzzk", "/live-privacy"],
  },
  {
    path: "/blog/live-face-blur", title: "How to Blur Bystanders During a Live Stream | InnoLive",
    description: "Register your guests and check automatic face detection before going live. Learn where to apply blur between camera input and broadcast output.",
    heading: "How to blur bystanders during a live stream",
    intro: "Apply face detection and blur between camera input and broadcast output to hide bystanders' faces. In InnoLive, register your guests to keep them visible. Check that unregistered faces are blurred before starting the broadcast.",
    sections: [
      { heading: "1. Check who appears in the camera view", paragraphs: ["Other people may enter the frame on streets, in shops or at events. Adjust framing and identify the guests you intend to show."] },
      { heading: "2. Account for the timing of live video", paragraphs: ["With recorded video, an editor can mark faces, review the result and export a file. Live video reaches viewers continuously, so later editing cannot change scenes they have already received."] },
      { heading: "3. Apply blur before output", paragraphs: ["Process camera input before sending it to the streaming platform. Inspect the output with real-time face anonymization enabled."] },
      { heading: "4. Check automatic detection", paragraphs: ["Test moving faces, small faces and side views. InnoLive updates blur positions from face detections, and results can vary with filming conditions."] },
      { heading: "5. Prepare your InnoLive broadcast", paragraphs: ["Register guests, check that unregistered faces are blurred, then prepare your platform account and broadcast settings.", "Use the web demo to inspect the effect. Follow the live face blur and platform guides below for broadcast preparation."] },
    ], related: ["/live-mosaic", "/live-privacy", "/youtube", "/chzzk"],
  },
  {
    path: "/blog/automatic-mosaic", title: "Automatic Face Blur: Recorded Video and Live Streams | InnoLive",
    description: "Compare face tracking in recorded-video editors with real-time face blur. InnoLive blurs unregistered faces before live video reaches viewers.",
    heading: "How automatic face blur works in video",
    intro: "For recorded video, detect and track faces, review the processed footage and export the file. Live streams need face processing before broadcast output. InnoLive blurs unregistered faces in live camera video in real time.",
    sections: [
      { heading: "Recorded video: detect, track and review", paragraphs: ["Import a recording into an editor and select the faces to hide. Editors with face detection and tracking can follow moving face regions.", "Replay the result to check for missed faces and misplaced masks. Correct them before export. Automatic detection and manual correction options vary by tool."] },
      { heading: "Live video: process before broadcasting", paragraphs: ["Process camera input and send the result to the platform as you broadcast. Check the preview and the platform's start procedure first.", "InnoLive keeps registered guests visible and blurs unregistered faces. Its current effect is blur, not pixelated mosaic."] },
      { heading: "Choose a tool for your video source", paragraphs: ["Use a recorded-video editor when you need to upload, correct and export a file. InnoLive processes live camera video and does not provide recorded-file editing or export.", "For live face blur, try the camera demo and check guest registration before preparing your broadcast in the app."] },
      { heading: "Keep guests visible during outdoor streams", paragraphs: ["Register the people you want to show before streaming. Try the camera demo, then follow the face registration and streaming software guides to prepare your broadcast."] },
    ],
    faq: [
      { question: "Can I edit recorded files as I would with CapCut mosaic?", answer: "InnoLive applies real-time blur to unregistered faces in live camera video. To import recordings, track and correct face masks, and export files, use a video editor that provides those features." },
      { question: "How is the web demo different from an online video blur editor?", answer: "The web demo lets you inspect face detection and blur on camera input. It does not offer video file uploads, edited-video review or processed-file downloads." },
    ], related: ["/face-mosaic", "/mosaic-software", "/free-face-blur", "/blog/live-face-blur"],
  },
];
