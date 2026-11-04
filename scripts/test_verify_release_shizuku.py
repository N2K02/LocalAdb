import unittest
import tempfile
from pathlib import Path
import zipfile
from verify_release_shizuku import STARTER, has_public_main, verify_starter


class StarterDexTest(unittest.TestCase):
    def fixture(self, access="PUBLIC STATIC", signature="([Ljava/lang/String;)V", name="main"):
        return (f"Class #0\nClass descriptor : '{STARTER}'\n"
                f"name : '{name}'\ntype : '{signature}'\naccess : 0x0009 ({access})\n")

    def test_public_static_main_is_accepted(self):
        self.assertTrue(has_public_main(self.fixture(), STARTER))

    def test_renamed_or_removed_class_is_rejected(self):
        self.assertFalse(has_public_main(self.fixture().replace(STARTER, "La/b;"), STARTER))

    def test_main_in_another_class_cannot_hide_missing_entrypoint(self):
        text = f"Class #0\nClass descriptor : '{STARTER}'\n" + self.fixture().replace(
            "Class #0", "Class #1").replace(STARTER, "La/b;")
        self.assertFalse(has_public_main(text, STARTER))

    def test_private_main_is_rejected(self):
        self.assertFalse(has_public_main(self.fixture(access="PRIVATE STATIC"), STARTER))

    def test_instance_main_is_rejected(self):
        self.assertFalse(has_public_main(self.fixture(access="PUBLIC"), STARTER))

    def test_wrong_signature_is_rejected(self):
        self.assertFalse(has_public_main(self.fixture(signature="()V"), STARTER))

    def test_non_utf8_dex_strings_do_not_hide_valid_entrypoint(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            tool = directory / "dexdump"
            output = b"unrelated modified UTF-8: \xc0\x80\n" + self.fixture().encode()
            tool.write_text("#!/usr/bin/env python3\nimport sys\nsys.stdout.buffer.write(" +
                            repr(output) + ")\n")
            tool.chmod(0o700)
            apk = directory / "fixture.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("classes.dex", b"fixture")
            with zipfile.ZipFile(apk) as archive:
                verify_starter(archive, tool, directory)


if __name__ == "__main__":
    unittest.main()
