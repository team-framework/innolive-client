#!/bin/sh
# Xcode Run Script phase of the InnoLive app target. Reads Xcode's build settings from the environment.
# Archive always fails without the three compiled face models. Release fails unless
# INNOLIVE_ALLOW_MISSING_MODELS=YES. Debug only warns, so builds and tests without models keep working.
app="${TARGET_BUILD_DIR:?}/${UNLOCALIZED_RESOURCES_FOLDER_PATH:?}"
missing=""
for model in PrivacyDetector PrivacyFaceRecognizer PrivacyYuNet; do
  [ -f "$app/$model.mlmodelc/coremldata.bin" ] || missing="$missing $model.mlmodelc"
done
[ -z "$missing" ] && exit 0

if [ "${ACTION:-build}" = install ]; then
  level=error
elif [ "${CONFIGURATION:-}" = Release ] && [ "${INNOLIVE_ALLOW_MISSING_MODELS:-NO}" != YES ]; then
  level=error
else
  level=warning
fi

echo "$level: InnoLive.app is missing$missing. Local mode needs all three face models. Place PrivacyDetector, PrivacyFaceRecognizer and PrivacyYuNet .mlpackage in apps/ios/InnoLive/InnoLive/Resources/ (docs/ios-app-store-release.md)."
[ "$level" = warning ]
