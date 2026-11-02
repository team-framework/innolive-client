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
REQUIRED_CATALOGS = ("Localizable", "InfoPlist")
# Info.plist keys are resolved by the system, so the compiler never extracts them.
NOT_EXTRACTED_TABLES = {"InfoPlist"}
# Ignore positional ordering but preserve each argument's type and multiplicity.
FORMAT = re.compile(r"%(?:\d+\$)?[-+ #0]*(?:\d+|\*)?(?:\.(?:\d+|\*))?(?:hh|ll|[hlLzjt])?[@diuoxXfFeEgGaAcCsSp]")


def placeholders(text):
    return collections.Counter(
        re.sub(r"^%\d+\$", "%", match)
        for match in FORMAT.findall(text.replace("%%", ""))
    )


def load_catalogs():
    """Every *.xcstrings in Resources is a lookup table; the two required ones must exist."""
    errors = []
    for name in REQUIRED_CATALOGS:
        path = RESOURCES / f"{name}.xcstrings"
        if not path.is_file():
            errors.append(f"Missing catalog: {path.relative_to(ROOT)}")
    catalogs = {path.stem: json.loads(path.read_text()) for path in sorted(RESOURCES.glob("*.xcstrings"))}
    return catalogs, errors


def load_stringsdata(directory):
    """Raw compiler-extracted keys per table from every *.stringsdata file, plus the file count."""
    extracted = collections.defaultdict(set)
    files = list(directory.glob("*.stringsdata"))
    for path in files:
        data = json.loads(path.read_text())
        for table, entries in data.get("tables", {}).items():
            extracted[table].update(entry["key"] for entry in entries)
    return dict(extracted), len(files)


def unused_keys(catalogs, extracted):
    """Catalog keys no source file extracts. A table absent from every file is unused as a whole."""
    return sorted(
        (table, key)
        for table, catalog in catalogs.items()
        if table not in NOT_EXTRACTED_TABLES
        for key in catalog["strings"]
        if key not in extracted.get(table, set())
    )


def validate(stringsdata_dir=None):
    catalogs, errors = load_catalogs()
    total = 0
    for name, catalog in catalogs.items():
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
        extracted, file_count = load_stringsdata(stringsdata_dir)
        if not file_count:
            errors.append(f"No compiler extraction files in {stringsdata_dir}")
        checked = 0
        for table, keys in sorted(extracted.items()):
            if table not in catalogs:
                continue
            for key in sorted(keys):
                # Empty text and number/object-only interpolation need no translation.
                if not key or not FORMAT.sub("", key).strip():
                    continue
                checked += 1
                if key not in catalogs[table]["strings"]:
                    errors.append(f"{table}/{key}: compiler-extracted key missing from catalog")
        for table, key in unused_keys(catalogs, extracted):
            errors.append(f"{table}/{key}: catalog key not extracted from any source file")
        print(f"Checked {checked} compiler-extracted keys from {file_count} source files")
    for error in errors:
        print(f"FAIL: {error}")
    if errors:
        return 1
    print(f"PASS: {total} translatable entries in {len(catalogs)} catalogs across ko/en/ja; format arguments and permissions match")
    return 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stringsdata-dir", type=Path, help="App Objects-normal/arm64 directory from a fresh Xcode build")
    args = parser.parse_args()
    sys.exit(validate(args.stringsdata_dir))
