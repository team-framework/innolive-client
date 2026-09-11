# iOS localization

## Scope and language selection

The iOS client supports Korean (`ko`), English (`en`), and Japanese (`ja`).
Korean remains the development language and final fallback. iOS selects the
best supported language from the user's preferred languages, including an
app-specific language selected in Settings. The app does not maintain a
separate language preference or override the system locale. Relaunch after
changing the app language.

Privacy Policy links use the same resolved app language (`/ko/privacy`,
`/en/privacy`, or `/ja/privacy`) on the existing website.

This is an iOS presentation change only. HTTP/WebRTC fields, persisted enum
raw values, stream state identifiers, and server contracts do not change.
User-entered text, channel names, server-provided free-form text, and system
error descriptions are not translated by the app. Dates use native locale
formatting; API timestamps retain their original parsing rules.

## Resources and authoring

- `apps/ios/InnoLive/InnoLive/Resources/Localizable.xcstrings` owns UI,
  accessibility, client-generated errors, and state descriptions.
- `apps/ios/InnoLive/InnoLive/Resources/InfoPlist.xcstrings` owns camera and
  microphone permission explanations.
- Catalog source language is `ko`; add complete `ko`, `en`, and `ja` entries.
- Generated catalog symbols are disabled because the app uses original text
  keys, including punctuation-only formats and punctuation variants that
  would collide as Swift identifiers.
- SwiftUI's localized literal initializers resolve catalog keys automatically.
  Ordinary `String` values do not: localize model/error messages with
  `String(localized:)`, and use localized key types for reusable static UI
  labels where appropriate.
- Never translate identifiers, storage keys, protocol values, asset names,
  diagnostic log text, URLs, or user-generated content.
- Preserve format-argument types and counts in translations. Do not build a
  sentence by concatenating separately translated fragments.

Terminology: 로그인 → Sign In / ログイン; 방송 → broadcast / 配信;
공개 → Public / 公開; 일부 공개 → Unlisted / 限定公開;
비공개 → Private / 非公開. Provider names remain branded names.

## Automated verification

From the repository root:

```sh
python3 scripts/validate-ios-localizations.py
xcodebuild test -project apps/ios/InnoLive/InnoLive.xcodeproj \
  -scheme InnoLive -destination 'platform=iOS Simulator,name=iPhone 17' \
  -testLanguage ko -testRegion KR CODE_SIGNING_ALLOWED=NO
```

Repeat focused `-only-testing:InnoLiveTests/LocalizationTests` runs with
`-testLanguage en -testRegion US` and `-testLanguage ja -testRegion JP`.
The catalog validator checks completed translations, untranslated Korean,
format-argument parity, and permission keys. `LocalizationTests` checks the
compiled app's actual language bundles, translated sign-in text, permission
resources, dynamic display titles, and unchanged wire values.

After a fresh build, pass the app's compiler extraction directory to also
detect missing source keys (including interpolation format keys):

```sh
python3 scripts/validate-ios-localizations.py --stringsdata-dir \
  /path/to/DerivedData/Build/Intermediates.noindex/InnoLive.build/Debug-iphonesimulator/InnoLive.build/Objects-normal/arm64
```

## Manual release checks (not implied by automated tests)

For each supported language, relaunch with that app language and inspect:

1. Sign-in, email entry, password, sign-up and verification screens, including
   validation errors, password visibility labels, and privacy/support links.
2. Home preview, camera/microphone controls, connection errors and live status.
3. Settings, account deletion confirmation, camera/audio pickers, and YouTube
   connection and broadcast configuration.
4. Face list, capture guidance, registration/removal errors and dates.
5. First-use camera/microphone prompts on a fresh install or reset permissions.
6. Long English/Japanese labels at small iPhone widths and large Dynamic Type;
   iPad layouts, VoiceOver labels, button reachability and text truncation.

Verify unsupported language fallback and language preference ordering with an
unsupported primary language. Real camera capture, provider authentication,
live broadcast and destructive account actions require dedicated test
accounts/devices and are not covered by string-resource tests.

## Implementation verification

Observed with Xcode 26.6 and an iPhone 17 / iOS 26.5 simulator:

- Full XCTest suite: 70 passed in Korean, 70 in English, and 70 in Japanese.
- Catalog validation: 309 translatable entries across both catalogs; all
  three translations completed, format arguments matched, and no missing
  compiler-extracted keys.
- Actual sign-in screen captures in all three languages showed translated
  labels without visible clipping at the default iPhone 17 text size.
- Existing camera actor-isolation warnings were present before this change.
- Full authenticated-screen visual review, Dynamic Type/iPad coverage,
  real-device permission prompts, and actual camera/broadcast flows remain
  unverified; the manual checklist above is still required for release.
