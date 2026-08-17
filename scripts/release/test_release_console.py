import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("release_console.py")
SPEC = importlib.util.spec_from_file_location("release_console", MODULE_PATH)
assert SPEC and SPEC.loader
release_console = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release_console)


class ReleaseConsoleTest(unittest.TestCase):
    def test_channels_follow_branch_topology(self):
        self.assertEqual(["alpha"], release_console.allowed_channels("codex/develop"))
        self.assertEqual(["alpha", "beta"], release_console.allowed_channels("codex/release/1.2.0"))
        self.assertEqual(["beta", "stable"], release_console.allowed_channels("main"))
        self.assertEqual([], release_console.allowed_channels("codex/feature/map"))

    def test_tag_suggestions_increment_prerelease_only(self):
        suggestions = release_console.suggested_tags(
            "codex/develop",
            ["v1.2.0-alpha.1", "v1.2.0-alpha.2", "v1.2.0-beta.1"],
        )
        self.assertEqual("v1.2.0-alpha.3", suggestions["alpha"])
        self.assertEqual("v1.2.0-beta.2", suggestions["beta"])
        self.assertEqual("v1.2.0", suggestions["stable"])

    def test_invalid_tags_are_rejected(self):
        self.assertIsNone(release_console.parse_tag("1.0.0"))
        self.assertIsNone(release_console.parse_tag("v1.0.0-alpha.0"))
        self.assertEqual("alpha", release_console.parse_tag("v1.0.0-alpha.1")["channel"])


if __name__ == "__main__":
    unittest.main()
