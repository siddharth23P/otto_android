"""The vendored guard rules must be byte-identical to otto's: the phone
enforces the copy, otto pre-checks the original, and a divergence is a
rule that one side does not know. The page corpus both guards are held to
(src/test/resources/guard_pages.json and its fixtures) is vendored the same
way: the phone's PageCorpusTest must judge the pages otto judged."""
import hashlib
import json
from importlib import resources
from pathlib import Path

from agent.phone import guard

VENDORED = Path(__file__).resolve().parents[2] / "assets" / "guard_rules.json"
PAGES = Path(__file__).resolve().parents[3] / "test" / "resources"


def test_vendored_rules_equal_ottos():
    assert VENDORED.read_text(encoding="utf-8") == guard.rules_text(), (
        f"copy otto's agent/phone/assets/guard_rules.json over {VENDORED}"
    )


def test_vendored_page_corpus_equals_ottos():
    ours = (PAGES / "guard_pages.json").read_text(encoding="utf-8")
    theirs = resources.files("agent.phone").joinpath("assets/guard_pages.json").read_text(encoding="utf-8")
    assert ours == theirs, f"copy otto's agent/phone/assets/guard_pages.json over {PAGES / 'guard_pages.json'}"
    manifest = json.loads(ours)
    vendored = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in (PAGES / "guard_pages").glob("*.json")}
    assert vendored == {name: page["sha256"] for name, page in manifest["pages"].items()}, (
        f"copy otto's tests/fixtures/phone_pages/*.json into {PAGES / 'guard_pages'}"
    )
