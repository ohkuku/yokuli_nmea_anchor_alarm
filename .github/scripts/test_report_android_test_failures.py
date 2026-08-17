import importlib.util
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("report_android_test_failures.py")
SPEC = importlib.util.spec_from_file_location("report_android_test_failures", MODULE_PATH)
assert SPEC and SPEC.loader
reporter = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(reporter)


class AndroidTestFailureReporterTest(unittest.TestCase):
    def test_extracts_failure_identity_and_message(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "TEST-device.xml").write_text(
                '<testsuite><testcase classname="WatchStory" name="disconnect">'
                '<failure message="expected alarm"/></testcase></testsuite>',
                encoding="utf-8",
            )
            original_root = reporter.RESULT_ROOT
            reporter.RESULT_ROOT = root
            try:
                self.assertEqual([("WatchStory.disconnect", "expected alarm")], reporter.xml_failures())
            finally:
                reporter.RESULT_ROOT = original_root

    def test_github_command_escaping(self):
        self.assertEqual("a%25b%0Ac", reporter.github_escape("a%b\nc"))


if __name__ == "__main__":
    unittest.main()
