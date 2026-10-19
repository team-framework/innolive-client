#!/bin/bash
set -euo pipefail
if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
  echo "Usage: $0 <physical-device-udid> [derived-data-path]" >&2
  exit 2
fi
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"
for model in PrivacyDetector PrivacyFaceRecognizer PrivacyYuNet; do
  if [ ! -d "apps/ios/InnoLive/InnoLive/Resources/$model.mlpackage" ]; then
    echo "Missing $model.mlpackage; prepare the local models first." >&2
    exit 1
  fi
done
if [ ! -f apps/ios/InnoLive/InnoLiveTests/PrivacyFaceFixtures/privacy-performance-face.png ]; then
  echo "Missing public face fixture; see docs/ios-privacy-performance-2026-10-19.md." >&2
  exit 1
fi
perf_derived_data="${2:-${TMPDIR:-/tmp}/innolive-privacy-performance}"
echo 'Performance configuration: Debug instrumentation, Swift -O, wholemodule, serial tests.'
xcodebuild build-for-testing \
  -project apps/ios/InnoLive/InnoLive.xcodeproj -scheme InnoLive \
  -configuration Debug -destination "platform=iOS,id=$1" \
  -derivedDataPath "$perf_derived_data" -parallel-testing-enabled NO \
  -allowProvisioningUpdates SWIFT_OPTIMIZATION_LEVEL=-O SWIFT_COMPILATION_MODE=wholemodule

# xctestrun supplies the argument to the test host, without changing everyday app launches.
perf_run_file="$(python3 - "$perf_derived_data/Build/Products" <<'PY'
import plistlib
import sys
from pathlib import Path
root = Path(sys.argv[1])
candidates = [p for p in root.glob('InnoLive_*.xctestrun') if 'iphoneos' in p.name]
if not candidates:
    raise SystemExit('No physical-device xctestrun found')
source = max(candidates, key=lambda p: p.stat().st_mtime)
data = plistlib.loads(source.read_bytes())
targets = [target for config in data.get('TestConfigurations', []) for target in config.get('TestTargets', [])]
if not targets:
    targets = [v for k, v in data.items() if isinstance(v, dict) and 'TestBundlePath' in v]
if not targets:
    raise SystemExit('No test targets in xctestrun')
for target in targets:
    args = target.setdefault('CommandLineArguments', [])
    if '--privacy-performance-test-host' not in args:
        args.append('--privacy-performance-test-host')
destination = root / 'privacy-performance.xctestrun'
destination.write_bytes(plistlib.dumps(data))
print(destination)
PY
)"
perf_tests=(
  -only-testing:InnoLiveTests/PrivacySegmentationTests
  -only-testing:InnoLiveTests/PrivacyMaskStabilizerTests
  -only-testing:InnoLiveTests/PrivacyUplinkTests
  -only-testing:InnoLiveTests/PrivacySustainedPerformanceTests
)
if [ -n "${INNOLIVE_PERF_TEST_FILTER:-}" ]; then
  perf_tests=("-only-testing:$INNOLIVE_PERF_TEST_FILTER")
fi
exec xcodebuild test-without-building -xctestrun "$perf_run_file" \
  -destination "platform=iOS,id=$1" -parallel-testing-enabled NO "${perf_tests[@]}"
