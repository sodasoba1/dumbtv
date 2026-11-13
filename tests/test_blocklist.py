"""Pi-hole exports stay version-compatible and keep risky services opt-in."""
from pathlib import Path
import unittest

DATA = Path(__file__).resolve().parents[1] / "data" / "blocklists" / "pihole"


def entries(path):
    return [line.strip() for line in path.read_text().splitlines()
            if line.strip() and not line.lstrip().startswith(("#", "!"))]


class PiHoleListsTest(unittest.TestCase):
    def test_default_list_excludes_prime_and_alexa(self):
        for version in ("v5", "v6", "v5-legacy"):
            with self.subTest(version=version):
                domains = entries(DATA / version / "amazon-extra.txt")
                joined = "\n".join(domains).lower()
                self.assertNotIn("aiv-delivery", joined)
                self.assertNotIn("aiv-cdn", joined)
                self.assertNotIn("device-messaging-na.amazon.com", joined)
                self.assertNotIn("api.amazonalexa.com", joined)

    def test_prime_video_endpoints_are_only_in_opt_in_list(self):
        for version in ("v5", "v6", "v5-legacy"):
            with self.subTest(version=version):
                domains = entries(DATA / version / "prime-video.txt")
                self.assertIn("device-messaging-na.amazon.com" if version == "v5-legacy" else
                              "||device-messaging-na.amazon.com^", domains)

    def test_adblock_exports_use_valid_abp_domain_rules(self):
        for version in ("v5", "v6"):
            for name in ("amazon-extra", "alexa", "prime-video"):
                with self.subTest(version=version, name=name):
                    rules = entries(DATA / version / f"{name}.txt")
                    self.assertTrue(rules)
                    self.assertTrue(all(x.startswith("||") and x.endswith("^") for x in rules))

    def test_legacy_exports_are_plain_domains(self):
        for name in ("amazon-extra", "alexa", "prime-video"):
            with self.subTest(name=name):
                domains = entries(DATA / "v5-legacy" / f"{name}.txt")
                self.assertTrue(domains)
                self.assertTrue(all("||" not in x and "^" not in x for x in domains))

    def test_prime_video_allowlist_is_plain_and_separate(self):
        allow = entries(DATA / "prime-video-allowlist.txt")
        block = entries(DATA / "v6" / "amazon-extra.txt")
        self.assertEqual(len(allow), 5)
        self.assertTrue(all("||" not in x and "^" not in x for x in allow))
        self.assertFalse(set(allow) & set(block))


if __name__ == "__main__":
    unittest.main()
