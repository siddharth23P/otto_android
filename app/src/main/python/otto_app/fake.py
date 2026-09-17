"""A scripted model, for running a whole turn on an emulator with no vendor key (#14).

Debug builds only: EmbeddedTransport switches it on when the file `files/otto_fake_model` exists,
which the instrumented smoke test writes before it starts the app. It replaces otto's pipeline --
the same two functions otto's own tests/test_embed.py replaces -- so everything around it is real:
the session, the event stream, the ask and its answer, and the phone tools, which call through
PyBridge into the accessibility service exactly as a model's calls would.

The turn: read the screen (phone_screen), open Settings > Display (phone_settings), ask "Which
one?" with two choices, then answer with what the phone said and the files the message carried. A request containing "slow" waits
first, so a test can kill the app while a turn runs.
"""
from __future__ import annotations

import logging
import os
import re
import time

log = logging.getLogger("otto_app.fake")

FLAG_FILE = "otto_fake_model"
ENV = "OTTO_FAKE_MODEL"
#: What a router needs to call itself ready; never sent anywhere, since no model is called.
PLACEHOLDER_KEY = "fake-model-no-calls"
QUESTION = "Which one?"
CHOICES = ["first", "second"]
THREAD = "fake-thread"
SLOW_S = 30.0

_state: dict = {"installed": False, "pipeline_import_s": None}


def enabled() -> bool:
    return os.environ.get(ENV) == "1"


def _first_line(result) -> str:
    text = (getattr(result, "stdout", "") or getattr(result, "stderr", "") or "").strip()
    return text.splitlines()[0][:120] if text else "(nothing)"


def install() -> None:
    """Swap the pipeline for the script. Idempotent."""
    if _state["installed"]:
        return
    started = time.monotonic()
    from agent import embed
    from agent.pipeline import nodes  # noqa: F401 -- imported for its cost, which #14 budgets
    from agent.pipeline import run as pipeline
    from agent.pipeline.progress import check_cancelled
    from agent.pipeline.toolkit import dispatch_table

    _state["pipeline_import_s"] = round(time.monotonic() - started, 2)
    log.info("fake model on; the pipeline imported in %.2f s", _state["pipeline_import_s"])
    seen: dict[str, str] = {}

    def fake_run(text, **kwargs):
        # Each attached file, by name, with the first words of its text (Attachments.compose's blocks).
        seen["files"] = "; ".join(
            f"{m.group(1)}: {' '.join(m.group(2).split())[:40]}"
            for m in re.finditer(r'<attached-file name="([^"]*)"[^>]*>\n\[[^\]]*\]\n(.*?)\n</attached-file>', text or "", re.S))
        if "slow" in (text or "").lower():
            deadline = time.monotonic() + SLOW_S
            while time.monotonic() < deadline:
                check_cancelled()
                time.sleep(0.2)
        table = dispatch_table()
        seen["screen"] = _first_line(table["phone_screen"]("{}")) if "phone_screen" in table else "no phone"
        yield {"agent": {"board": [f"solve: phone_screen {seen['screen']}"], "output": None}}
        seen["settings"] = (_first_line(table["phone_settings"]('{"page": "display"}'))
                            if "phone_settings" in table else "no phone")
        yield {"agent": {"board": [f"solve: phone_settings {seen['settings']}"], "output": None}}
        yield {"__ask__": {"question": QUESTION, "choices": CHOICES, "thread_id": THREAD}}

    def fake_resume(answer, **kwargs):
        yield {"__final__": {"final_output": f"fake answer: you picked {answer}. "
                                             f"screen: {seen.get('screen')}. settings: {seen.get('settings')}. "
                                             f"attached: {seen.get('files') or 'nothing'}"},
               "__trace_id__": None}

    pipeline.run_pipeline_stream = fake_run
    pipeline.resume_pipeline_stream = fake_resume
    # No model call decides whether the turn uses the phone: it does.
    embed.DECIDE_PHONE = False
    _state["installed"] = True


def pipeline_import_seconds() -> float:
    """How long importing otto's pipeline took when the script was installed; -1 before that."""
    value = _state["pipeline_import_s"]
    return -1.0 if value is None else float(value)
