"""The embedded runtime's logs: where they go, what they never carry, and that every call Kotlin
makes is in them (2026-09-16: a failed probe left nothing anywhere)."""
from __future__ import annotations

import json
import logging

import pytest

from otto_app import logs


@pytest.fixture
def fresh_logging():
    root = logging.getLogger()
    handlers, level = list(root.handlers), root.level
    logs._state["path"] = ""
    yield
    for handler in list(root.handlers):
        if handler not in handlers:
            root.removeHandler(handler)
            handler.close()
    root.setLevel(level)
    logs._state["path"] = ""


def test_setup_writes_a_file_under_the_home_once(tmp_path, fresh_logging):
    path = logs.setup(str(tmp_path), debug=True)
    assert path == str(tmp_path / "logs" / "otto.log")
    assert logs.setup(str(tmp_path)) == path
    logging.getLogger("agent.router").warning("anthropic: unreachable")
    for handler in logging.getLogger().handlers:
        handler.flush()
    text = (tmp_path / "logs" / "otto.log").read_text(encoding="utf-8")
    assert "logging to" in text and "agent.router: anthropic: unreachable" in text


def test_nothing_key_shaped_is_written(tmp_path, fresh_logging):
    logs.setup(str(tmp_path))
    log = logging.getLogger("somewhere")
    log.warning("key %s and %s", "sk-ant-api03-abcdefghijklmnop1234", "AIzaSyA1234567890abcdefghijklmn")
    log.warning("Authorization: Bearer abcdefgh12345678")
    for handler in logging.getLogger().handlers:
        handler.flush()
    text = (tmp_path / "logs" / "otto.log").read_text(encoding="utf-8")
    assert "sk-ant-api03" not in text and "AIzaSyA" not in text and "abcdefgh12345678" not in text
    assert "…1234" in text and "…klmn" in text


def test_every_call_is_logged_and_a_key_value_never(caplog):
    from otto_app import entry

    def set_key(name, value):
        return json.dumps({"ok": False, "error": {"code": "invalid", "message": "not a vendor"}})

    wrapped = entry._logged(set_key)
    with caplog.at_level(logging.INFO, logger="otto_app.entry"):
        wrapped("ANTHROPIC_API_KEY", "sk-ant-secret-value-123456")
    line = caplog.records[-1].getMessage()
    assert line.startswith("set_key('ANTHROPIC_API_KEY', <hidden>) -> invalid: not a vendor")
    assert "secret" not in line
    assert caplog.records[-1].levelno == logging.WARNING


def test_the_entry_functions_kotlin_calls_are_wrapped_and_keep_their_signatures():
    import inspect

    from otto_app import entry

    assert entry.probe.__wrapped__.__name__ == "probe"
    assert "phone" in inspect.signature(entry.start_turn).parameters or \
        list(inspect.signature(entry.start_turn).parameters) == ["session_id", "text"]
    assert not hasattr(entry.missing, "__wrapped__")


def test_tls_trusts_certifi_unless_a_bundle_is_already_chosen(monkeypatch, tmp_path):
    import certifi

    from otto_app import bootstrap

    monkeypatch.delenv("SSL_CERT_FILE", raising=False)
    assert bootstrap.trust_store() == certifi.where()
    import os

    assert os.environ["SSL_CERT_FILE"] == certifi.where()
    mine = tmp_path / "ca.pem"
    mine.write_text("x")
    monkeypatch.setenv("SSL_CERT_FILE", str(mine))
    assert bootstrap.trust_store() == str(mine)
