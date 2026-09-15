"""What Kotlin calls. Every argument and return value is a JSON string, so
the bridge is one shape in both directions and a Swift port would be the
same file.

A turn runs on a Python thread this module starts; its events go back
through `PyBridge.onEvent(json)`. `answer` and `cancel` only set flags on the
running handle, so they are safe from the UI thread.

Everything `otto serve` answers (agent/server/app.py) is here too, calling
the same agent/embed.py functions with the same validation and replying
with the same field names, so one set of Kotlin models reads both. The
embedded host is this app, trusted the way serve trusts a loopback client:
keys and routing may be changed, but a key's value is never echoed. A
function whose otto API is missing (the pinned otto can be older) is left
out of this module altogether -- see `NEEDS` at the bottom -- and Kotlin
reads its absence as "needs a newer otto".
"""
from __future__ import annotations

import json
import re
import threading
from typing import Any

from otto_app import backend as _backend
from otto_app import bootstrap, compat

_runtime = None
_handles: dict[str, Any] = {}
_turns: dict[str, threading.Thread] = {}
_lock = threading.Lock()

#: As agent/server/app.py.
MAX_TITLE_CHARS = 200
MAX_KEY_CHARS = 512
MAX_SPEC_CHARS = 200
PHONE_MODES: dict[str, bool | None] = {"auto": None, "on": True, "off": False}

#: agent/memory/sessions.py SESSION_ID_RE, for an otto that predates valid_id.
_SESSION_ID = re.compile(r"[0-9a-f]{32}")


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


def _valid_id(value: Any) -> bool:
    return isinstance(value, str) and _SESSION_ID.fullmatch(value) is not None


def _invalid_session(value: Any) -> str:
    return _err(f"{str(value)[:80]!r} is not a session id", code="invalid_session")


def _busy(session_id: str) -> bool:
    """A turn is running, or its thread is about to start one. Caller holds
    no lock requirement: dict reads are atomic."""
    handle = _handles.get(session_id)
    thread = _turns.get(session_id)
    return bool((handle is not None and handle.running) or (thread is not None and thread.is_alive()))


def _any_busy() -> bool:
    """A turn is running anywhere: a key, a pin or a lesson changes what
    every turn reads."""
    return any(_busy(sid) for sid in set(_handles) | set(_turns))


# --------------------------------------------------------------------------
# setup, doctor, models
# --------------------------------------------------------------------------

def setup_status() -> str:
    if not bootstrap.available():
        return _ok(available=False, ready=False, keys={}, version={}, compat={})
    from agent import embed

    try:
        c = compat.probe()
        cdata = {"otto": c.otto_version, "api": c.api_version, "features": list(c.features)}
    except compat.IncompatibleOtto as exc:
        return _err(str(exc), code="incompatible")
    if bootstrap.is_configured() and compat.has("setup_status"):
        data = embed.setup_status()
    else:
        data = {"ready": bootstrap.is_configured() and embed.ready(), "keys": embed.key_status(),
                "version": embed.version()}
    data["ready"] = bool(bootstrap.is_configured() and data.get("ready"))
    return _ok(available=True, setup_write=True, compat=cdata, **data)


def set_key(name: str, value: str) -> str:
    """Nothing here may put `value` into a reply: every message is built
    from the name and the mask."""
    from agent import embed

    names = tuple(getattr(embed, "KEY_VARS", ()))
    if names and name not in names:
        return _err(f"name is one of {', '.join(names)}", code="invalid")
    if not isinstance(value, str) or len(value) > MAX_KEY_CHARS or any(c in value for c in "\r\n\x00"):
        return _err(f"a key is one line of at most {MAX_KEY_CHARS} characters", code="invalid")
    if _any_busy():
        return _err("a turn is running; change keys when it has finished", code="busy")
    try:
        masked = embed.set_key(name, value)
    except ValueError:
        return _err(f"{str(name)[:40]!r} is not a key name", code="invalid")
    return _ok(name=name, masked=masked, ready=bool(embed.ready()))


def probe(name: str) -> str:
    """A probe that ran but failed is still `ok: false` on this bridge, which
    Kotlin reads as an error -- so its status and detail are the message."""
    from agent import embed

    try:
        result = embed.probe(name if isinstance(name, str) else "")
    except ValueError as exc:
        return _err(str(exc), code="invalid")
    if not result.get("ok"):
        detail = result.get("detail") or ""
        return _err(f"{result.get('name', name)}: {result.get('status') or 'failed'}" + (f" — {detail}" if detail else ""),
                    code="probe_failed")
    return _ok(**{k: v for k, v in result.items() if k != "ok"})


def doctor() -> str:
    from agent import embed

    if compat.has("doctor_report"):
        return _ok(**embed.doctor_report())
    return _ok(providers=embed.doctor())


def models() -> str:
    from agent import embed

    return _ok(models=embed.models())


def guard_rules_text() -> str:
    """otto's guard rules verbatim, so Kotlin can check its vendored copy."""
    from agent.phone import guard

    return guard.rules_text()


# --------------------------------------------------------------------------
# sessions
# --------------------------------------------------------------------------

def _runtime_or_error():
    global _runtime
    if _runtime is None:
        from agent import embed

        _runtime = embed.Runtime()
    return _runtime


def _handle_for(ref: str | None):
    """The handle this app holds for `ref`, else one opened and held. A
    prefix or "last" that names a held session returns the held one, which
    may be running. LookupError as `Runtime.open_session` raises it."""
    with _lock:
        held = _handles.get(ref) if ref else None
    if held is not None:
        return held
    handle = _runtime_or_error().open_session(ref or None)
    with _lock:
        existing = _handles.get(handle.id)
        if existing is None:
            _handles[handle.id] = handle
            return handle
    handle.close()
    return existing


def list_sessions(limit: int = 20) -> str:
    return _ok(sessions=_runtime_or_error().list_sessions(limit=limit))


def open_session(ref: str = "") -> str:
    try:
        handle = _handle_for(ref or None)
    except LookupError as exc:
        return _err(str(exc), code="no_session")
    return _ok(session_id=handle.id, title=handle.title, turns=handle.turns)


def transcript(ref: str) -> str:
    try:
        return _ok(**_runtime_or_error().transcript(ref))
    except LookupError as exc:
        return _err(str(exc), code="no_session")


def delete_session(session_id: str) -> str:
    if not _valid_id(session_id):
        return _invalid_session(session_id)
    with _lock:
        if _busy(session_id):
            return _err("that session is running a turn; stop it first", code="busy")
        handle = _handles.pop(session_id, None)
        _turns.pop(session_id, None)
    if handle is not None:
        handle.close()
    return _ok(session_id=session_id, deleted=_runtime_or_error().delete_session(session_id))


def close_session(session_id: str) -> str:
    """Let go of a held session; its saved history stays."""
    if not _valid_id(session_id):
        return _invalid_session(session_id)
    with _lock:
        if _busy(session_id):
            return _err("that session is running a turn; stop it first", code="busy")
        handle = _handles.pop(session_id, None)
        _turns.pop(session_id, None)
    if handle is not None:
        handle.close()
    return _ok(session_id=session_id, closed=handle is not None)


def rename_session(session_id: str, title: str) -> str:
    if not _valid_id(session_id):
        return _invalid_session(session_id)
    if not isinstance(title, str) or not title.strip() or len(title) > MAX_TITLE_CHARS:
        return _err(f"title is 1-{MAX_TITLE_CHARS} characters", code="invalid")
    with _lock:
        handle = _handles.get(session_id)
    try:
        stored = handle.rename(title) if handle is not None else _runtime_or_error().rename_session(session_id, title)
    except LookupError as exc:
        return _err(str(exc), code="no_session")
    except ValueError as exc:
        return _err(str(exc), code="invalid")
    return _ok(session_id=session_id, title=stored)


def export_session(session_id: str) -> str:
    """{"filename", "data"}: the export without the workspace path."""
    if not _valid_id(session_id):
        return _invalid_session(session_id)
    try:
        exported = _runtime_or_error().export_session(session_id)
    except LookupError as exc:
        return _err(str(exc), code="no_session")
    return _ok(session_id=session_id, **exported)


def import_session(data: Any) -> str:
    """`data` is what export_session returned as `data`, as a JSON string (or
    already decoded). The workspace never comes in with it."""
    if isinstance(data, str):
        try:
            data = json.loads(data)
        except ValueError:
            data = None
    if not isinstance(data, dict):
        return _err("data is the object a sessions export returned", code="invalid")
    try:
        row = _runtime_or_error().import_session(data)
    except ValueError as exc:
        return _err(str(exc), code="invalid")
    return _ok(session_id=row["id"], title=row["title"], turns=row["turns"])


def session_usage(session_id: str) -> str:
    if not _valid_id(session_id):
        return _invalid_session(session_id)
    with _lock:
        handle = _handles.get(session_id)
    if handle is None:
        return _err("that session is not open", code="no_session")
    return _ok(session_id=session_id, **handle.usage_report())


def read_file(session_id: str, name: str) -> str:
    """A research document from the session's workspace (embed
    `Runtime.document_file`, which confines the path). Read-only."""
    from agent import embed

    if not _valid_id(session_id):
        return _invalid_session(session_id)
    try:
        result = _runtime_or_error().document_file(session_id, name)
    except embed.FileTooLarge as exc:
        return _err(str(exc), code="too_large")
    except FileNotFoundError as exc:
        return _err(str(exc), code="not_found")
    except ValueError as exc:
        return _err(str(exc), code="invalid")
    return _ok(session_id=session_id, **result)


# --------------------------------------------------------------------------
# turns
# --------------------------------------------------------------------------

def _budget_max() -> int | None:
    """The ceiling a turn runs under when nobody bound one, for "12 of 40"."""
    try:
        from agent.pipeline.budget import default_budget

        return int(default_budget().max_model_calls)
    except Exception:  # an otto without it: the UI shows no ceiling
        return None


def _start_turn(session_id: str, text: str, phone: str | None) -> str:
    text = (text or "").strip()
    if not text:
        return _err("say something first", code="empty")
    if phone is not None and phone not in PHONE_MODES:
        return _err("phone is one of auto, on, off", code="invalid")
    if session_id and not _valid_id(session_id):
        return _invalid_session(session_id)
    try:
        handle = _handle_for(session_id or None)
    except LookupError as exc:
        return _err(str(exc), code="no_session")
    session_id = handle.id
    from agent.phone import PHONE_DISABLED_STANDING_TOOLS, PHONE_GUIDANCE, phone_tools

    tools = phone_tools(_backend.kotlin_phone_backend())
    kwargs: dict[str, Any] = {"events": _emit, "tools": tools}
    if compat.has("guidance"):
        kwargs["guidance"] = PHONE_GUIDANCE
    if compat.has("disabled_tools"):
        kwargs["disabled_tools"] = PHONE_DISABLED_STANDING_TOOLS
    if phone is not None and compat.has("phone_decision"):
        # Off the phone, embed's default keeps the subprocess tools away from
        # keystore keys (off_phone_disabled_tools=None); not overridden here.
        kwargs["phone"] = PHONE_MODES[phone]
    budget = _budget_max()
    started: dict[str, Any] = {"type": "started", "session_id": session_id}
    if budget is not None:
        started["budget_max"] = budget

    def run() -> None:
        _emit(started)
        handle.run(text, **kwargs)

    thread = threading.Thread(target=run, name=f"otto-turn-{session_id[:8]}", daemon=True)
    with _lock:
        if _busy(session_id):
            return _err("a turn is already running", code="busy")
        _turns[session_id] = thread
    thread.start()
    extra = {"budget_max": budget} if budget is not None else {}
    return _ok(session_id=session_id, **extra)


if compat.has("phone_decision"):
    def start_turn(session_id: str, text: str, phone: str = "auto") -> str:
        """`phone`: auto (otto decides), on or off. Kotlin sees this
        parameter in the signature and offers the choice."""
        return _start_turn(session_id, text, phone)
else:
    def start_turn(session_id: str, text: str) -> str:
        return _start_turn(session_id, text, None)


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


# --------------------------------------------------------------------------
# routing
# --------------------------------------------------------------------------

def routes() -> str:
    from agent import embed

    return _ok(routes=embed.routing())


def route_options(task: str) -> str:
    from agent import embed

    try:
        return _ok(**embed.routing_options(task if isinstance(task, str) else ""))
    except ValueError as exc:
        return _err(str(exc), code="invalid")


def _change_route(task: Any, spec: Any) -> str:
    from agent import embed
    from agent.router.mapping import Task
    from agent.router.overrides import PinError

    tasks = [t.value for t in Task]
    if not isinstance(task, str) or task not in tasks:
        return _err(f"task is one of {', '.join(tasks)}", code="invalid")
    if spec is not None and (not isinstance(spec, str) or not spec.strip() or len(spec) > MAX_SPEC_CHARS):
        return _err("spec is provider:model", code="invalid")
    if _any_busy():
        return _err("a turn is running; change routing when it has finished", code="busy")
    try:
        problems = embed.set_pin(task, spec) if spec is not None else embed.clear_pin(task)
    except PinError as exc:
        return _err(str(exc), code="invalid_pin")
    return _ok(task=task, pin=spec.strip() if spec is not None else None, problems=list(problems))


def pin_route(task: str, spec: str) -> str:
    return _change_route(task, spec if spec is not None else "")


def clear_route(task: str) -> str:
    return _change_route(task, None)


# --------------------------------------------------------------------------
# lessons and app notes
# --------------------------------------------------------------------------

_KIND_MESSAGE = "kind is lesson, phone_lesson or app_note:<package>"
_LESSON_ID_MESSAGE = "lesson_id is 64 hex characters"
_BUSY_LEARNING = "a turn is running; change what otto learned when it has finished"


def list_lessons(kind: str) -> str:
    from agent import embed
    from agent.memory import lessons as L

    if not L.valid_kind(kind):
        return _err(_KIND_MESSAGE, code="invalid")
    return _ok(**embed.lessons(kind))


def delete_lesson(kind: str, lesson_id: str) -> str:
    from agent import embed
    from agent.memory import lessons as L

    if not L.valid_kind(kind):
        return _err(_KIND_MESSAGE, code="invalid")
    if not L.valid_lesson_id(lesson_id):
        return _err(_LESSON_ID_MESSAGE, code="invalid")
    if _any_busy():
        return _err(_BUSY_LEARNING, code="busy")
    return _ok(kind=kind, lesson_id=lesson_id, deleted=embed.delete_lesson(kind, lesson_id))


def clear_lessons(kind: str) -> str:
    from agent import embed
    from agent.memory import lessons as L

    if not L.valid_kind(kind):
        return _err(_KIND_MESSAGE, code="invalid")
    if _any_busy():
        return _err(_BUSY_LEARNING, code="busy")
    return _ok(kind=kind, removed=embed.clear_lessons(kind))


def list_notes() -> str:
    from agent import embed

    return _ok(notes=embed.notes())


def get_note(package: str) -> str:
    from agent import embed
    from agent.phone.notes import valid_package

    if not valid_package(package):
        return _err("package is an Android package name", code="invalid")
    return _ok(**embed.note(package))


def delete_note(package: str, lesson_id: str) -> str:
    from agent import embed
    from agent.memory import lessons as L
    from agent.phone.notes import valid_package

    if not valid_package(package):
        return _err("package is an Android package name", code="invalid")
    if not L.valid_lesson_id(lesson_id):
        return _err(_LESSON_ID_MESSAGE, code="invalid")
    if _any_busy():
        return _err(_BUSY_LEARNING, code="busy")
    return _ok(package=package, lesson_id=lesson_id, deleted=embed.delete_note(package, lesson_id))


# --------------------------------------------------------------------------
# what this otto can answer
# --------------------------------------------------------------------------

#: Each function beyond the first embedding API, and the compat feature it
#: needs. The names are EmbeddedTransport.FUNCTIONS'.
NEEDS: dict[str, str] = {
    "rename_session": "rename",
    "export_session": "export",
    "import_session": "export",
    "session_usage": "usage",
    "probe": "probe",
    "models": "models",
    "routes": "routing",
    "route_options": "routing",
    "pin_route": "routing",
    "clear_route": "routing",
    "list_lessons": "lessons",
    "delete_lesson": "lessons",
    "clear_lessons": "lessons",
    "list_notes": "notes",
    "get_note": "notes",
    "delete_note": "notes",
    "read_file": "files",
}


def missing(has=None) -> list[str]:
    """The functions this otto cannot back."""
    has = has or compat.has
    return sorted(name for name, feature in NEEDS.items() if not has(feature))


for _name in missing():
    globals().pop(_name, None)
