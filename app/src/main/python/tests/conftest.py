"""Host-side tests for the bridge: otto is the pinned release (or the git
pin before it exists), the Kotlin side is a fake, no keys, no network."""
from __future__ import annotations

import base64
import json
import os
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

for _name in ("INCEPTION_API_KEY", "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GEMINI_API_KEY"):
    os.environ.setdefault(_name, "test-placeholder-not-a-real-key")
os.environ.setdefault("OTTO_EMBEDDING_MODEL", "")

PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 32

SCREEN = {"snapshot_id": "s1", "app": {"package": "com.android.settings", "label": "Settings"},
          "screen": {"w": 1080, "h": 2400}, "keyboard": False, "secure": False,
          "nodes": [{"i": 1, "t": "Display", "d": "", "r": "text", "b": [0, 0, 100, 40], "c": False, "e": False,
                     "s": False, "p": False, "f": False, "k": None},
                    {"i": 2, "t": "Font size", "d": "", "r": "text", "b": [60, 900, 900, 960], "c": True, "e": False,
                     "s": False, "p": False, "f": False, "k": None}]}


class FakePyBridge:
    """Stands in for dev.otto.phone.bridge.PyBridge."""

    def __init__(self):
        self.events = []
        self.calls = []

    def _ok(self, data):
        return json.dumps({"ok": True, "data": data})

    def onEvent(self, payload):
        self.events.append(json.loads(payload))

    def tree(self):
        self.calls.append(("tree",)); return self._ok(SCREEN)

    def foreground(self):
        return self._ok(SCREEN["app"])

    def tap(self, x, y):
        self.calls.append(("tap", x, y)); return self._ok({"done": "tapped", "after": SCREEN})

    def tap_node(self, sid, i, long, commit):
        self.calls.append(("tap_node", sid, i, long, commit)); return self._ok({"done": "tapped", "after": SCREEN})

    def type_text(self, text, i):
        self.calls.append(("type_text", text, i)); return self._ok({"done": "typed", "after": SCREEN})

    def press(self, key):
        self.calls.append(("press", key)); return self._ok({"done": key, "after": SCREEN})

    def swipe(self, direction):
        return self._ok({"done": direction, "after": SCREEN})

    def scroll(self, direction, i):
        return self._ok({"done": direction, "after": SCREEN})

    def screenshot(self):
        return self._ok({"png_b64": base64.b64encode(PNG).decode()})

    def apps(self):
        return self._ok({"apps": [{"label": "Settings", "package": "com.android.settings"}]})

    def launch(self, package):
        return self._ok({"package": package, "label": "Settings", "after": SCREEN})

    def open_settings(self, page, package):
        self.calls.append(("open_settings", page, package)); return self._ok({"page": page, "after": SCREEN})

    def install(self, package, query):
        return self._ok({"state": "installing", "after": SCREEN})


@pytest.fixture
def bridge(tmp_path, monkeypatch):
    from otto_app import backend, bootstrap, entry

    fake = FakePyBridge()
    backend.use_bridge(fake)
    monkeypatch.setattr(bootstrap, "_state", {"configured": False, "home": "", "available": None, "error": ""})
    monkeypatch.setattr(entry, "_runtime", None)
    monkeypatch.setattr(entry, "_handles", {})
    monkeypatch.setattr(entry, "_turns", {})
    from agent import embed

    monkeypatch.setattr(embed, "_configured", {})
    # otto's own test isolation for the session index and memory store
    from agent.memory import sessions as _sessions
    from agent.memory import store as _store

    monkeypatch.setattr(_store, "DB_DIR", tmp_path / "memory")
    with _sessions.bind_index(tmp_path / "sessions.db"):
        yield fake
