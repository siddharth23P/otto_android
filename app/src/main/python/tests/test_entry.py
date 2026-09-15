"""entry.py end to end with a fake PyBridge and a fake pipeline."""
import json
import threading
import time

from agent.pipeline import run as pipeline
from agent.pipeline.toolkit import dispatch_table
from agent.pipeline.tools import reachable_tools
from otto_app import bootstrap, entry


def _wait(pred, timeout=10):
    end = time.time() + timeout
    while time.time() < end:
        if pred():
            return True
        time.sleep(0.02)
    return False


KEYS = {name: "test-placeholder-not-a-real-key" for name in ("INCEPTION_API_KEY", "ANTHROPIC_API_KEY", "GEMINI_API_KEY")}


def test_bootstrap_and_setup_status(bridge, tmp_path, monkeypatch):
    # Keys arrive from the keystore and nothing else: naming them clears
    # whatever the process had (otto 0.2.0's configure()).
    status = json.loads(bootstrap.configure(str(tmp_path / "otto"), json.dumps({**KEYS, "OPENAI_API_KEY": "sk-keystore1234"})))
    assert status == {"ok": True, "available": True, "home": str(tmp_path / "otto")}
    assert (tmp_path / "otto").is_dir()
    info = json.loads(entry.setup_status())
    assert info["ok"] and info["available"] and info["ready"]
    assert info["keys"]["OPENAI_API_KEY"] == "********1234"
    assert info["compat"]["api"] == 1
    assert not list((tmp_path / "otto").glob("*.env"))  # keystore mode: nothing written
    assert json.loads(entry.set_key("OPENAI_API_KEY", "sk-rotated5678"))["masked"] == "********5678"
    assert not json.loads(entry.set_key("bad name", "x"))["ok"]


def test_a_turn_reaches_the_phone_and_events_reach_kotlin(bridge, tmp_path, monkeypatch):
    bootstrap.configure(str(tmp_path / "otto"), json.dumps(KEYS))
    observed = {}

    def fake_run(text, **kwargs):
        observed["bash"] = "execute_bash" in reachable_tools()
        observed["screen"] = dispatch_table()["phone_screen"]("{}").stdout
        observed["settings"] = dispatch_table()["phone_settings"]('{"page": "display"}').ok
        yield {"agent": {"board": ["looked"]}}
        yield {"__ask__": {"question": "Bigger or smaller?", "choices": ["bigger", "smaller"], "thread_id": "t1"}}

    def fake_resume(answer, **kwargs):
        yield {"__final__": {"final_output": f"made it {answer}"}, "__trace_id__": None}

    monkeypatch.setattr(pipeline, "run_pipeline_stream", fake_run)
    monkeypatch.setattr(pipeline, "resume_pipeline_stream", fake_resume)

    opened = json.loads(entry.open_session(""))
    sid = opened["session_id"]
    assert json.loads(entry.start_turn(sid, "change my font size"))["ok"]
    assert _wait(lambda: any(e["type"] == "ask" for e in bridge.events))
    assert json.loads(entry.start_turn(sid, "again"))["error"]["code"] == "busy"
    assert json.loads(entry.answer(sid, "t1", "bigger"))["ok"]
    assert _wait(lambda: any(e["type"] == "final" for e in bridge.events))
    kinds = [e["type"] for e in bridge.events]
    assert kinds[:2] == ["started", "board"] and kinds[-1] == "final"
    assert bridge.events[-1]["text"] == "made it bigger"
    assert observed["bash"] is False and '"Font size"' in observed["screen"] and observed["settings"]
    assert ("open_settings", "display", "") in bridge.calls
    listed = json.loads(entry.list_sessions(5))["sessions"]
    assert listed[0]["id"] == sid and listed[0]["turns"] == 1
    assert json.loads(entry.transcript(sid))["messages"][0]["role"] == "you"
    assert json.loads(entry.delete_session(sid))["deleted"] is True


def test_cancel_stops_a_waiting_turn(bridge, tmp_path, monkeypatch):
    bootstrap.configure(str(tmp_path / "otto"), json.dumps(KEYS))

    def fake_run(text, **kwargs):
        yield {"__ask__": {"question": "?", "choices": [], "thread_id": "t"}}

    monkeypatch.setattr(pipeline, "run_pipeline_stream", fake_run)
    sid = json.loads(entry.open_session(""))["session_id"]
    entry.start_turn(sid, "hi")
    assert _wait(lambda: any(e["type"] == "ask" for e in bridge.events))
    entry.cancel(sid)
    assert _wait(lambda: any(e["type"] == "error" and e["code"] == "cancelled" for e in bridge.events))
    assert json.loads(entry.answer(sid, "t", "late"))["error"]["code"] == "no_question"


def test_the_bridge_runs_off_the_calling_thread(bridge, tmp_path, monkeypatch):
    bootstrap.configure(str(tmp_path / "otto"), json.dumps(KEYS))
    seen = {}

    def fake_run(text, **kwargs):
        seen["thread"] = threading.current_thread().name
        yield {"__final__": {"final_output": "ok"}}

    monkeypatch.setattr(pipeline, "run_pipeline_stream", fake_run)
    sid = json.loads(entry.open_session(""))["session_id"]
    entry.start_turn(sid, "x")
    assert _wait(lambda: any(e["type"] == "final" for e in bridge.events))
    assert seen["thread"].startswith("otto-turn-")
