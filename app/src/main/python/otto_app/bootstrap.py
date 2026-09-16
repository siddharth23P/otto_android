"""First thing the app runs in Python, before any `import agent`.

`agent.config.home` reads OTTO_HOME when the state modules are imported, so
the home has to be set here, from the app's private files directory, before
anything else touches otto. Keys are handed in from the Kotlin keystore
(environ mode) so no .env is ever written on the phone.
"""
from __future__ import annotations

import importlib
import json
import logging
import os
import sys

from otto_app import logs

log = logging.getLogger("otto_app.bootstrap")

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
            log.error("otto is not importable in this build", exc_info=True)
    return bool(_state["available"])


def configure(home: str, keys_json: str = "{}", debug: bool = False) -> str:
    """Point otto at `home` and inject `keys` (a JSON object of env var ->
    value). Idempotent. Returns a JSON status the Kotlin side shows. Logging
    starts first, so even a build that cannot import otto says why."""
    logs.setup(home, bool(debug))
    if not available():
        return json.dumps({"ok": False, "available": False, "error": _state["error"]})
    keys = json.loads(keys_json or "{}")
    present = sorted(name for name, value in keys.items() if value)
    log.info("configuring otto at %s; keys given: %s", home, ", ".join(present) or "none")
    from agent import embed

    try:
        embed.configure(home, environ={k: v for k, v in keys.items() if v})
    except RuntimeError as exc:
        log.exception("otto would not configure")
        return json.dumps({"ok": False, "available": True, "error": str(exc)})
    _state.update(configured=True, home=home)
    os.environ.setdefault("OTTO_NO_ANIMATION", "1")
    try:
        from importlib.metadata import version

        otto = version("otto-cli-agent")
    except Exception:
        otto = "?"
    log.info("otto %s ready on Python %s", otto, sys.version.split()[0])
    return json.dumps({"ok": True, "available": True, "home": home})


def is_configured() -> bool:
    return bool(_state["configured"])
