"""What Kotlin calls. Every argument and return value is a JSON string, so
the bridge is one shape in both directions and a Swift port would be the
same file.

A turn runs on a Python thread this module starts; its events go back
through `PyBridge.onEvent(json)`. `answer` and `cancel` only set flags on the
running handle, so they are safe from the UI thread.
"""
from __future__ import annotations

import json
import threading
from typing import Any

from otto_app import backend as _backend
from otto_app import bootstrap, compat

_runtime = None
_handles: dict[str, Any] = {}
_turns: dict[str, threading.Thread] = {}
_lock = threading.Lock()


def _ok(**data: Any) -> str:
    return json.dumps({"ok": True, **data})


def _err(message: str, code: str = "failed") -> str:
    return json.dumps({"ok": False, "error": {"code": code, "message": message}})


def _emit(event: dict) -> None:
    payload = json.dumps(event)
    try:
        _backend.bridge().onEvent(payload)
    except Exception:  # a UI that cannot draw must not stop the run
        pass


def setup_status() -> str:
    if not bootstrap.available():
        return _ok(available=False, ready=False, keys={}, version={}, compat={})
    from agent import embed

    try:
        c = compat.probe()
        cdata = {"otto": c.otto_version, "api": c.api_version, "features": list(c.features)}
    except compat.IncompatibleOtto as exc:
        return _err(str(exc), code="incompatible")
    return _ok(available=True, ready=bool(bootstrap.is_configured() and embed.ready()),
               keys=embed.key_status(), version=embed.version(), compat=cdata)


def set_key(name: str, value: str) -> str:
    from agent import embed

    try:
        return _ok(name=name, shown=embed.set_key(name, value))
    except ValueError as exc:
        return _err(str(exc), code="invalid")


def doctor() -> str:
    from agent import embed

    return _ok(providers=embed.doctor())


def guard_rules_text() -> str:
    """otto's guard rules verbatim, so Kotlin can check its vendored copy."""
    from agent.phone import guard

    return guard.rules_text()


def _runtime_or_error():
    global _runtime
    if _runtime is None:
        from agent import embed

        _runtime = embed.Runtime()
    return _runtime


def list_sessions(limit: int = 20) -> str:
    return _ok(sessions=_runtime_or_error().list_sessions(limit=limit))


def open_session(ref: str = "") -> str:
    try:
        handle = _runtime_or_error().open_session(ref or None)
    except LookupError as exc:
        return _err(str(exc), code="no_session")
    with _lock:
        _handles[handle.id] = handle
    return _ok(session_id=handle.id, title=handle.title, turns=handle.turns)


def transcript(ref: str) -> str:
    try:
        return _ok(**_runtime_or_error().transcript(ref))
    except LookupError as exc:
        return _err(str(exc), code="no_session")


def delete_session(session_id: str) -> str:
    with _lock:
        handle = _handles.pop(session_id, None)
    if handle is not None:
        handle.close()
    return _ok(deleted=_runtime_or_error().delete_session(session_id))


def start_turn(session_id: str, text: str) -> str:
    text = (text or "").strip()
    if not text:
        return _err("say something first", code="empty")
    with _lock:
        handle = _handles.get(session_id)
    if handle is None:
        return _err("open the session first", code="no_session")
    if handle.running:
        return _err("a turn is already running", code="busy")
    from agent.phone import PHONE_DISABLED_STANDING_TOOLS, PHONE_GUIDANCE, phone_tools

    tools = phone_tools(_backend.kotlin_phone_backend())
    kwargs: dict[str, Any] = {"events": _emit, "tools": tools}
    if compat.has("guidance"):
        kwargs["guidance"] = PHONE_GUIDANCE
    if compat.has("disabled_tools"):
        kwargs["disabled_tools"] = PHONE_DISABLED_STANDING_TOOLS

    def run() -> None:
        _emit({"type": "started", "session_id": session_id})
        handle.run(text, **kwargs)

    thread = threading.Thread(target=run, name=f"otto-turn-{session_id[:8]}", daemon=True)
    with _lock:
        _turns[session_id] = thread
    thread.start()
    return _ok(session_id=session_id)


def answer(session_id: str, thread_id: str, text: str) -> str:
    with _lock:
        handle = _handles.get(session_id)
    if handle is None or not handle.answer(thread_id, text):
        return _err("no question is waiting", code="no_question")
    return _ok()


def cancel(session_id: str) -> str:
    with _lock:
        handle = _handles.get(session_id)
    if handle is not None:
        handle.cancel()
    return _ok()
