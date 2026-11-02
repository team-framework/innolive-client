#!/usr/bin/env python3
"""Unit tests for validate-ios-localizations.py. Run: python3 scripts/test_validate_ios_localizations.py"""

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().with_name("validate-ios-localizations.py")
spec = importlib.util.spec_from_file_location("validate_ios_localizations", SCRIPT)
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)


def catalog(*keys):
    return {"sourceLanguage": "ko", "strings": {key: {} for key in keys}}


class LoadStringsdataTests(unittest.TestCase):
    def test_collects_raw_keys_per_table_across_files(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / "A.stringsdata").write_text(json.dumps({
                "tables": {"Localizable": [{"key": "a"}, {"key": "%lld/%lld"}], "CHZZK": [{"key": "c"}]}
            }))
            (path / "B.stringsdata").write_text(json.dumps({"tables": {"Localizable": [{"key": "b"}]}}))
            (path / "ignored.txt").write_text("{}")
            extracted, count = validator.load_stringsdata(path)
        self.assertEqual(extracted, {"Localizable": {"a", "b", "%lld/%lld"}, "CHZZK": {"c"}})
        self.assertEqual(count, 2)


class UnusedKeysTests(unittest.TestCase):
    def test_reports_catalog_keys_the_compiler_did_not_extract(self):
        catalogs = {"Localizable": catalog("used", "stale")}
        self.assertEqual(
            validator.unused_keys(catalogs, {"Localizable": {"used"}}),
            [("Localizable", "stale")],
        )

    def test_skips_plist_table_that_is_never_extracted(self):
        catalogs = {"InfoPlist": catalog("NSCameraUsageDescription")}
        self.assertEqual(validator.unused_keys(catalogs, {}), [])

    def test_table_absent_from_every_source_file_is_fully_unused(self):
        catalogs = {"CHZZK": catalog("b", "a")}
        self.assertEqual(
            validator.unused_keys(catalogs, {"Localizable": {"x"}}),
            [("CHZZK", "a"), ("CHZZK", "b")],
        )

    def test_extracted_key_missing_from_catalog_is_not_unused(self):
        catalogs = {"Localizable": catalog("a")}
        self.assertEqual(validator.unused_keys(catalogs, {"Localizable": {"a", "new"}}), [])


if __name__ == "__main__":
    unittest.main()
