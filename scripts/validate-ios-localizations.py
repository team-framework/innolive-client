#!/usr/bin/env python3
"""Validate iOS string catalog translations without Xcode or dependencies."""

import argparse
import collections
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "apps/ios/InnoLive/InnoLive/Resources"
LANGUAGES = ("ko", "en", "ja")
# Ignore positional ordering but preserve each argument's type and multiplicity.
FORMAT = re.compile(r"%(?:\d+\$)?[-+ #0]*(?:\d+|\*)?(?:\.(?:\d+|\*))?(?:hh|ll|[hlLzjt])?[@diuoxXfFeEgGaAcCsSp]")


def placeholders(text):
    return collections.Counter(
        re.sub(r"^%\d+\$", "%", match)
        for match in FORMAT.findall(text.replace("%%", ""))
    )


def validate(stringsdata_dir=None):
    errors = []
    total = 0
    catalogs = {}
    for name in ("Localizable", "InfoPlist"):
        path = RESOURCES / f"{name}.xcstrings"
        if not path.is_file():
            errors.append(f"Missing catalog: {path.relative_to(ROOT)}")
            continue
        catalog = json.loads(path.read_text())
        catalogs[name] = catalog
        if catalog.get("sourceLanguage") != "ko":
            errors.append(f"{name}: sourceLanguage must remain ko")
        for key, entry in catalog["strings"].items():
            if entry.get("shouldTranslate") is False:
                continue
            total += 1
            translations = entry.get("localizations", {})
            for language in LANGUAGES:
                unit = translations.get(language, {}).get("stringUnit", {})
                value = unit.get("value")
                if value is None or (key and not value) or unit.get("state") != "translated":
                    errors.append(f"{name}/{language}/{key}: missing completed translation")
                    continue
                if language != "ko" and re.search(r"[가-힣]", value):
                    errors.append(f"{name}/{language}/{key}: untranslated Korean")
                source = translations.get("ko", {}).get("stringUnit", {}).get("value", key)
                if placeholders(value) != placeholders(source):
                    errors.append(f"{name}/{language}/{key}: format arguments differ")
        if name == "InfoPlist":
            for key in ("NSCameraUsageDescription", "NSMicrophoneUsageDescription"):
                if key not in catalog["strings"]:
                    errors.append(f"InfoPlist: missing {key}")
    if stringsdata_dir is not None:
        extracted = set()
        files = list(stringsdata_dir.glob("*.stringsdata"))
        if not files:
            errors.append(f"No compiler extraction files in {stringsdata_dir}")
        for path in files:
            data = json.loads(path.read_text())
            for table, entries in data.get("tables", {}).items():
                if table not in catalogs:
                    continue
                for entry in entries:
                    key = entry["key"]
                    # Empty text and number/object-only interpolation need no translation.
                    if not key or not FORMAT.sub("", key).strip():
                        continue
                    extracted.add((table, key))
        for table, key in sorted(extracted):
            if key not in catalogs[table]["strings"]:
                errors.append(f"{table}/{key}: compiler-extracted key missing from catalog")
        print(f"Checked {len(extracted)} compiler-extracted keys from {len(files)} source files")
    for error in errors:
        print(f"FAIL: {error}")
    if errors:
        return 1
    print(f"PASS: {total} translatable entries across ko/en/ja; format arguments and permissions match")
    return 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stringsdata-dir", type=Path, help="App Objects-normal/arm64 directory from a fresh Xcode build")
    args = parser.parse_args()
    sys.exit(validate(args.stringsdata_dir))
