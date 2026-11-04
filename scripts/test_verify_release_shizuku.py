import unittest
from verify_release_shizuku import STARTER, has_public_main


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


if __name__ == "__main__":
    unittest.main()
