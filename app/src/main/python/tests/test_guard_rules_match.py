"""The vendored guard rules must be byte-identical to otto's: the phone
enforces the copy, otto pre-checks the original, and a divergence is a
rule that one side does not know."""
from pathlib import Path

from agent.phone import guard

VENDORED = Path(__file__).resolve().parents[2] / "assets" / "guard_rules.json"


def test_vendored_rules_equal_ottos():
    assert VENDORED.read_text(encoding="utf-8") == guard.rules_text(), (
        f"copy otto's agent/phone/assets/guard_rules.json over {VENDORED}"
    )
