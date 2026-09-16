"""The operations `otto serve` answers, answered by entry.py: same
validation, same field names (app/src/test/.../ServeShapesTest.kt reads
both). A fake pipeline, a fake PyBridge, no network."""
import base64
import importlib
import inspect
import json
import threading
import time

import pytest

from agent.pipeline import run as pipeline
from agent.pipeline.toolkit import dispatch_table
from agent.pipeline.tools import reachable_tools
from otto_app import bootstrap, compat, entry

AMAZON = "in.amazon.mShop.android.shopping"  # the note conftest's lesson_bank seeds

pytestmark = pytest.mark.skipif(
    not (compat.has("phone_decision") and all(compat.has(f) for f in compat.SERVE_FEATURES)),
    reason="needs an otto with the serve operations in agent/embed.py")

KEYS = {name: "test-placeholder-not-a-real-key"
        for name in ("INCEPTION_API_KEY", "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GEMINI_API_KEY")}


def _wait(pred, timeout=10):
    end = time.time() + timeout
    while time.time() < end:
        if pred():
            return True
        time.sleep(0.02)
    return False


def _j(reply: str) -> dict:
    return json.loads(reply)


def _code(reply: str) -> str:
    frame = _j(reply)
    assert frame["ok"] is False, frame
    return frame["error"]["code"]


def _configure(tmp_path):
    bootstrap.configure(str(tmp_path / "otto"), json.dumps(KEYS))


def _finals(bridge):
    return [e for e in bridge.events if e["type"] == "final"]


def _one_turn(bridge, monkeypatch, text="what tides are", phone="auto"):
    def fake_run(t, **kwargs):
        yield {"__final__": {"final_output": "hi back"}, "__trace_id__": None}

    monkeypatch.setattr(pipeline, "run_pipeline_stream", fake_run)
    before = len(_finals(bridge))
    started = _j(entry.start_turn("", text, phone))
    assert started["ok"], started
    sid = started["session_id"]
    assert _wait(lambda: len(_finals(bridge)) > before)
    assert _wait(lambda: not entry._busy(sid))
    return sid


@pytest.fixture
def held_turn(bridge, tmp_path, monkeypatch):
    """A turn that runs until the test lets it go."""
    _configure(tmp_path)
    gate = threading.Event()

    def fake_run(text, **kwargs):
        gate.wait(5)
        yield {"__final__": {"final_output": "ok"}, "__trace_id__": None}

    monkeypatch.setattr(pipeline, "run_pipeline_stream", fake_run)
    sid = _j(entry.start_turn("", "hold on"))["session_id"]
    assert _wait(lambda: any(e["type"] == "started" for e in bridge.events))
    yield sid
    gate.set()
    assert _wait(lambda: not entry._busy(sid))


# -- turns -----------------------------------------------------------------------------------------

def test_start_turn_takes_the_phone_mode_and_puts_the_budget_on_started(bridge, tmp_path, monkeypatch):
    _configure(tmp_path)
    assert "phone" in inspect.signature(entry.start_turn).parameters  # what Kotlin feature-detects
    seen = {}

    def fake_run(text, **kwargs):
        seen["phone_tools"] = "phone_screen" in dispatch_table()
        seen["bash"] = "execute_bash" in reachable_tools()
        yield {"__final__": {"final_output": "answered here"}, "__trace_id__": None}

    monkeypatch.setattr(pipeline, "run_pipeline_stream", fake_run)
    reply = _j(entry.start_turn("", "what is a tide", "off"))  # no session yet: one is opened, as serve does
    assert reply["ok"] and isinstance(reply["budget_max"], int)
    assert _wait(lambda: _finals(bridge))
    started = bridge.events[0]
    assert started == {"type": "started", "session_id": reply["session_id"], "budget_max": reply["budget_max"]}
    phase = next(e for e in bridge.events if e["type"] == "progress" and e["kind"] == "phase")
    assert phase["detail"] == {"phone": False}
    assert seen == {"phone_tools": False, "bash": False}, "off the phone, keystore keys keep the shell off"
    assert _finals(bridge)[-1]["phone"] is False
    assert _code(entry.start_turn(reply["session_id"], "again", "sideways")) == "invalid"
    assert _code(entry.start_turn("../x", "again")) == "invalid_session"


def test_writes_wait_for_a_running_turn(held_turn, lesson_bank, routes_file):
    sid = held_turn
    assert _code(entry.start_turn(sid, "again")) == "busy"
    assert _code(entry.close_session(sid)) == "busy"
    assert _code(entry.delete_session(sid)) == "busy"
    assert _code(entry.set_key("OPENAI_API_KEY", "sk-new")) == "busy"
    assert _code(entry.pin_route("reason", "openai:gpt-5-mini")) == "busy"
    assert _code(entry.clear_lessons("lesson")) == "busy"
    note_id = lesson_bank["app_note:" + AMAZON][0]
    assert _code(entry.delete_note(AMAZON, note_id)) == "busy"
    assert _j(entry.list_lessons("lesson"))["ok"], "reading is never refused"
    assert not routes_file.exists()


# -- sessions --------------------------------------------------------------------------------------

def test_close_session(bridge, tmp_path, monkeypatch):
    _configure(tmp_path)
    sid = _one_turn(bridge, monkeypatch)
    assert _j(entry.close_session(sid)) == {"ok": True, "session_id": sid, "closed": True}
    assert _j(entry.close_session(sid))["closed"] is False
    assert sid not in entry._handles
    assert _code(entry.close_session("../x")) == "invalid_session"


def test_rename_session(bridge, tmp_path, monkeypatch):
    _configure(tmp_path)
    sid = _one_turn(bridge, monkeypatch)
    assert _j(entry.rename_session(sid, "  tide   notes ")) == {"ok": True, "session_id": sid, "title": "tide notes"}
    assert _j(entry.list_sessions(5))["sessions"][0]["title"] == "tide notes"
    entry.close_session(sid)
    assert _j(entry.rename_session(sid, "closed"))["title"] == "closed"
    assert _code(entry.rename_session("f" * 32, "x")) == "no_session"
    assert _code(entry.rename_session(sid, "   ")) == "invalid"
    assert _code(entry.rename_session(sid, "x" * 201)) == "invalid"


def test_export_and_import_session(bridge, tmp_path, monkeypatch):
    _configure(tmp_path)
    sid = _one_turn(bridge, monkeypatch)
    exported = _j(entry.export_session(sid))
    assert exported["ok"] and exported["session_id"] == sid and exported["filename"].startswith("otto-session-")
    assert exported["data"]["session"]["workspace"] is None
    imported = _j(entry.import_session(json.dumps(exported["data"])))  # Kotlin sends a JSON string
    assert imported["ok"] and imported["session_id"] != sid
    assert imported["title"] == "what tides are" and imported["turns"] == 1
    for bad in ("not json", "[]", "{}"):
        assert _code(entry.import_session(bad)) == "invalid", bad
    assert _code(entry.export_session("b" * 32)) == "no_session"


def test_session_usage(bridge, tmp_path, monkeypatch):
    _configure(tmp_path)
    sid = _one_turn(bridge, monkeypatch)
    usage = _j(entry.session_usage(sid))
    assert usage["ok"] and usage["session_id"] == sid
    assert set(usage) >= {"usage", "turn_tokens", "turn", "title", "turns"}
    assert len(usage["turn_tokens"]) == 1 and usage["turns"] == 1
    assert _code(entry.session_usage("c" * 32)) == "no_session"


def test_delete_session_checks_the_id(bridge, tmp_path, monkeypatch):
    _configure(tmp_path)
    sid = _one_turn(bridge, monkeypatch)
    assert _j(entry.delete_session(sid)) == {"ok": True, "session_id": sid, "deleted": True}
    assert _code(entry.delete_session("../../sessions")) == "invalid_session"


def test_read_file(bridge, tmp_path, monkeypatch):
    from agent import embed

    _configure(tmp_path)
    sid = _one_turn(bridge, monkeypatch)
    folder = embed.session_workspace(sid) / "otto_research" / "tides"
    folder.mkdir(parents=True)
    (folder / "document.docx").write_bytes(b"\x00\x01\x02")
    blob = _j(entry.read_file(sid, "document.docx"))
    assert blob["ok"] and blob["session_id"] == sid and blob["path"] == "otto_research/tides/document.docx"
    assert blob["format"] == "docx" and blob["size"] == 3 and base64.b64decode(blob["data"]) == b"\x00\x01\x02"
    for bad in ("../document.md", "otto_research/../../document.md", "notes.txt"):
        assert _code(entry.read_file(sid, bad)) == "invalid", bad
    assert _code(entry.read_file(sid, "document.pdf")) == "not_found"
    assert _code(entry.read_file("../x", "document.md")) == "invalid_session"


# -- setup, doctor, models -------------------------------------------------------------------------

def test_setup_status_and_set_key(bridge, tmp_path):
    from agent import embed

    _configure(tmp_path)
    raw = entry.setup_status()
    status = _j(raw)
    assert status["ok"] and status["ready"] and status["setup_write"] is True
    assert set(status["keys"]) == set(embed.KEY_VARS)
    assert {row["name"] for row in status["vendors"]} >= {"inception", "openai"}
    assert "test-placeholder-not-a-real-key" not in raw
    raw = entry.set_key("GEMINI_API_KEY", "AIza-rotated9x9x")
    assert _j(raw) == {"ok": True, "name": "GEMINI_API_KEY", "masked": "********9x9x", "ready": True}
    assert "rotated" not in raw
    assert _code(entry.set_key("PATH", "x")) == "invalid"
    assert "line1" not in entry.set_key("OPENAI_API_KEY", "line1\nline2")
    assert _code(entry.set_key("OPENAI_API_KEY", "line1\nline2")) == "invalid"


def test_probe(bridge, tmp_path, monkeypatch):
    from agent import embed

    _configure(tmp_path)
    assert _code(entry.probe("nope")) == "invalid"
    monkeypatch.setattr(embed, "probe", lambda name: {"name": name, "ok": True, "status": "ok", "detail": "",
                                                      "model_count": 1, "models": []})
    assert _j(entry.probe("openai")) == {"ok": True, "name": "openai", "status": "ok", "detail": "",
                                         "model_count": 1, "models": []}
    monkeypatch.setattr(embed, "probe", lambda name: {"name": name, "ok": False, "status": "auth",
                                                      "detail": "key rejected", "model_count": 0, "models": []})
    failed = _j(entry.probe("openai"))
    assert failed["error"] == {"code": "probe_failed", "message": "openai: auth — key rejected"}


def test_doctor(bridge, tmp_path, monkeypatch):
    from agent import embed

    _configure(tmp_path)
    monkeypatch.setattr(embed, "doctor", lambda: [{"provider": "inception", "status": "ok", "models": 3, "detail": ""}])
    report = _j(entry.doctor())
    assert report["ok"] and report["providers"][0]["provider"] == "inception"
    assert report["ready"] is True and report["required"] == "inception"
    assert "openai" in report["also_configured"]


def test_models(bridge, tmp_path, monkeypatch):
    from agent.router import llm_provider
    from agent.router.llm_provider.base import Capability, ModelInfo

    _configure(tmp_path)
    monkeypatch.setattr(llm_provider, "all_models", lambda capability=None: [
        ModelInfo("gpt-5-mini", "openai", capabilities=frozenset({Capability.CHAT}))])
    listed = _j(entry.models())
    assert listed["ok"] and listed["models"][0]["spec"] == "openai:gpt-5-mini"
    assert set(listed["models"][0]) == {"spec", "provider", "id", "display_name", "capabilities",
                                        "context_window", "max_output_tokens"}


# -- routing ---------------------------------------------------------------------------------------

def test_routes(bridge, tmp_path, routes_file):
    _configure(tmp_path)
    rows = _j(entry.routes())["routes"]
    by = {row["task"]: row for row in rows}
    assert set(by["reason"]) == {"task", "pin", "default", "provider_only", "phone_seat"}
    assert all(row["pin"] is None for row in rows)


def test_route_options(bridge, tmp_path, monkeypatch):
    from agent.router import llm_provider
    from agent.router.llm_provider.base import Capability, ModelInfo

    _configure(tmp_path)
    monkeypatch.setattr(llm_provider, "all_models", lambda capability=None: [
        ModelInfo("claude-x", "anthropic", capabilities=frozenset({Capability.CHAT, Capability.TOOLS}))])
    options = _j(entry.route_options("web"))
    assert options["task"] == "web" and options["options"][0] == {"label": "(no pin — default route)", "spec": ""}
    assert _code(entry.route_options("../x")) == "invalid"


def test_pin_route(bridge, tmp_path, routes_file):
    _configure(tmp_path)
    pinned = _j(entry.pin_route("reason", " openai:gpt-5-mini "))
    assert pinned["ok"] and pinned["task"] == "reason" and pinned["pin"] == "openai:gpt-5-mini"
    assert isinstance(pinned["problems"], list)
    assert json.loads(routes_file.read_text())["pins"]["reason"] == "openai:gpt-5-mini"
    assert _code(entry.pin_route("web", "openai:gpt-5-mini")) == "invalid_pin"
    assert _code(entry.pin_route("nope", "openai:x")) == "invalid"
    assert _code(entry.pin_route("reason", "")) == "invalid"


def test_clear_route(bridge, tmp_path, routes_file):
    _configure(tmp_path)
    entry.pin_route("reason", "openai:gpt-5-mini")
    cleared = _j(entry.clear_route("reason"))
    assert cleared["ok"] and cleared["task"] == "reason" and cleared["pin"] is None
    assert isinstance(cleared["problems"], list)
    assert "reason" not in json.loads(routes_file.read_text()).get("pins", {})
    assert _code(entry.clear_route("../x")) == "invalid"


# -- lessons and notes -----------------------------------------------------------------------------

def test_list_lessons(bridge, tmp_path, lesson_bank):
    _configure(tmp_path)
    listed = _j(entry.list_lessons("lesson"))
    assert listed["kind"] == "lesson"
    assert listed["lessons"] == [{"lesson_id": lesson_bank["lesson"][0], "cue": "a build is slow",
                                  "action": "cache the wheel", "outcome": "failed",
                                  "text": "When a build is slow: cache the wheel [failed]"}]
    for bad in ("app_note:../x", "nope", "app_note:", None):
        assert _code(entry.list_lessons(bad)) == "invalid", bad


def test_delete_lesson(bridge, tmp_path, lesson_bank):
    _configure(tmp_path)
    gone = lesson_bank["lesson"][0]
    assert _j(entry.delete_lesson("lesson", gone)) == {"ok": True, "kind": "lesson", "lesson_id": gone, "deleted": True}
    assert _j(entry.delete_lesson("lesson", gone))["deleted"] is False
    assert _code(entry.delete_lesson("lesson", gone.upper())) == "invalid"
    assert _code(entry.delete_lesson("app_note:../x", gone)) == "invalid"


def test_clear_lessons(bridge, tmp_path, lesson_bank):
    _configure(tmp_path)
    assert _j(entry.clear_lessons("phone_lesson")) == {"ok": True, "kind": "phone_lesson", "removed": 1}
    assert _j(entry.list_lessons("phone_lesson"))["lessons"] == []
    assert _code(entry.clear_lessons("app_note:a/b")) == "invalid"


def test_list_notes(bridge, tmp_path, lesson_bank):
    _configure(tmp_path)
    listed = {row["package"]: row for row in _j(entry.list_notes())["notes"]}
    assert listed[AMAZON] == {"package": AMAZON, "seeded": True, "learned": 1}
    assert listed["com.android.settings"]["seeded"] is True


def test_get_note(bridge, tmp_path, lesson_bank):
    _configure(tmp_path)
    got = _j(entry.get_note(AMAZON))
    assert got["package"] == AMAZON and got["seeded"]
    assert [row["lesson_id"] for row in got["learned"]] == lesson_bank["app_note:" + AMAZON]
    assert any("sponsored items are marked ad" in line for line in got["shown"])
    assert _code(entry.get_note("../x")) == "invalid"


def test_delete_note(bridge, tmp_path, lesson_bank):
    _configure(tmp_path)
    note_id = lesson_bank["app_note:" + AMAZON][0]
    assert _j(entry.delete_note(AMAZON, note_id)) == {"ok": True, "package": AMAZON, "lesson_id": note_id,
                                                      "deleted": True}
    assert _j(entry.get_note(AMAZON))["learned"] == []
    assert _code(entry.delete_note("a/b", note_id)) == "invalid"
    assert _code(entry.delete_note(AMAZON, "x")) == "invalid"


# -- an older otto ---------------------------------------------------------------------------------

def test_an_older_otto_leaves_the_functions_out(monkeypatch):
    """What Kotlin sees against otto 0.1.2: none of the serve operations,
    and a start_turn without `phone`, so the app says "needs a newer otto"."""
    old = {"disabled_tools", "guidance", "environ_keys", "transcript", "phone_commit"}
    real_has = compat.has
    assert entry.missing() == []
    try:
        monkeypatch.setattr(compat, "has", lambda feature: feature in old and real_has(feature))
        importlib.reload(entry)
        for name in entry.NEEDS:
            assert not hasattr(entry, name), name
        assert "phone" not in inspect.signature(entry.start_turn).parameters
        assert hasattr(entry, "close_session") and hasattr(entry, "setup_status")
    finally:
        monkeypatch.undo()
        importlib.reload(entry)
    assert all(hasattr(entry, name) for name in entry.NEEDS)
