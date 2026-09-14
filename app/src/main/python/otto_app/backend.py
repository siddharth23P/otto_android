"""The phone as otto sees it: agent.phone's JsonBackend over the Kotlin
bridge. `PyBridge` is a Kotlin object with @JvmStatic methods that take
positional arguments in the order agent/phone/backend.py documents and
return JSON envelopes; Chaquopy hands it to Python as a class."""
from __future__ import annotations

from typing import Any

_bridge: Any = None


def use_bridge(bridge: Any) -> None:
    """Inject the Kotlin class (or a fake, in tests)."""
    global _bridge
    _bridge = bridge


def bridge() -> Any:
    global _bridge
    if _bridge is None:
        from java import jclass  # Chaquopy; only importable on the device

        _bridge = jclass("dev.otto.phone.bridge.PyBridge")
    return _bridge


def kotlin_phone_backend():
    from agent.phone import JsonBackend

    return JsonBackend(bridge())
