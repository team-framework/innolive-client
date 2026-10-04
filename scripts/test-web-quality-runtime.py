"""Verify runtime secret injection without Docker or reading a real env file."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class QualityRuntimeTest(unittest.TestCase):
    def render(self, present):
        source = Path(__file__).with_name("deploy-innolive-web.sh").read_text()
        start = source.index("write_release_override() {")
        end = source.index("\n}\n", start) + 3
        with tempfile.TemporaryDirectory() as temp:
            env_path = Path(temp) / "web-quality.env"
            if present:
                env_path.write_text("EXPERIENCE_QUALITY_INGEST_KEY=private-test-value\n")
            output = Path(temp) / "override.yml"
            env = os.environ.copy()
            env.update(QUALITY_ENV_FILE=str(env_path), release_source="/tmp/release", expected_sha="a" * 40,
                       NEXT_PUBLIC_INNOLIVE_SERVER_URL="https://api.example.test",
                       NEXT_PUBLIC_IOS_DOWNLOAD_URL="https://example.test/ios",
                       NEXT_PUBLIC_ANDROID_DOWNLOAD_URL="https://example.test/android", private_log="/dev/null")
            result = subprocess.run(["bash", "-c", source[start:end] + '\nwrite_release_override "$1" test-image', "test", str(output)], env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            body = output.read_text()
            self.assertNotIn("private-test-value", body + result.stdout + result.stderr)
            self.assertNotIn("EXPERIENCE_QUALITY_INGEST_KEY", body)
            self.assertEqual(output.stat().st_mode & 0o777, 0o600)
            return body

    def test_runtime_env_file_only(self):
        body = self.render(True)
        self.assertIn("env_file: !override", body)
        self.assertIn("web-quality.env", body)
        self.assertIn("depends_on: !reset {}", body)

    def test_missing_runtime_file_keeps_collection_unconfigured(self):
        body = self.render(False)
        self.assertIn("env_file: !reset []", body)
        self.assertNotIn("web-quality.env", body)


if __name__ == "__main__":
    unittest.main()
