"""First thing the app runs in Python, before any `import agent`.

`agent.config.home` reads OTTO_HOME when the state modules are imported, so
the home has to be set here, from the app's private files directory, before
anything else touches otto. Keys are handed in from the Kotlin keystore
(environ mode) so no .env is ever written on the phone.
"""
from __future__ import annotations

import importlib
import json
import os

_state: dict = {"configured": False, "home": "", "available": None, "error": ""}


def available() -> bool:
    """Whether otto is importable in this APK at all. False in a build made
    without `-PembeddedPython=true`, where the app uses the serve transport."""
    if _state["available"] is None:
        try:
            importlib.import_module("agent.config.home")
            _state["available"] = True
        except Exception as exc:  # ImportError, or a wheel missing its native half
            _state["available"] = False
            _state["error"] = f"{type(exc).__name__}: {exc}"
    return bool(_state["available"])


def configure(home: str, keys_json: str = "{}") -> str:
    """Point otto at `home` and inject `keys` (a JSON object of env var ->
    value). Idempotent. Returns a JSON status the Kotlin side shows."""
    if not available():
        return json.dumps({"ok": False, "available": False, "error": _state["error"]})
    keys = json.loads(keys_json or "{}")
    from agent import embed

    try:
        embed.configure(home, environ={k: v for k, v in keys.items() if v})
    except RuntimeError as exc:
        return json.dumps({"ok": False, "available": True, "error": str(exc)})
    _state.update(configured=True, home=home)
    os.environ.setdefault("OTTO_NO_ANIMATION", "1")
    return json.dumps({"ok": True, "available": True, "home": home})


def is_configured() -> bool:
    return bool(_state["configured"])
