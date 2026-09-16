"""Where the embedded runtime's logs go.

Two places, so whoever is debugging a phone can read them either way: stderr, which Chaquopy
sends to logcat as `python.stderr` (`adb logcat -s python.stderr`), and a bounded file under
otto's home, `<home>/logs/otto.log` (`adb exec-out run-as dev.otto.phone cat
files/otto/logs/otto.log`). Before this a probe that failed inside the runtime left nothing
anywhere: the vendor's error was caught, cut to "Connection error." and dropped (2026-09-16).

Nothing secret is written: key values never reach a log call in this app, and a filter masks
anything shaped like an API key or a bearer token that a library might print anyway.
"""
from __future__ import annotations

import logging
import logging.handlers
import os
import re
import sys
import threading

FORMAT = "%(asctime)s %(levelname).1s %(threadName)s %(name)s: %(message)s"
MAX_BYTES = 1_000_000
BACKUPS = 2
#: Libraries that say too much below INFO, or that print request options: never below INFO.
QUIET = ("openai._base_client", "anthropic._base_client", "urllib3", "langsmith", "opentelemetry",
         "google_genai", "asyncio", "hpack")

_SECRET = re.compile(r"(sk-ant-[A-Za-z0-9_\-]{8,}|sk-[A-Za-z0-9_\-]{16,}|AIza[0-9A-Za-z_\-]{20,}"
                     r"|(?<=Bearer )[A-Za-z0-9._\-]{8,})")

_state: dict = {"path": ""}


def _mask(match: re.Match) -> str:
    value = match.group(0)
    return f"…{value[-4:]}"


class Redact(logging.Filter):
    """Masks key-shaped text in a record before any handler writes it."""

    def filter(self, record: logging.LogRecord) -> bool:
        message = record.getMessage()
        masked = _SECRET.sub(_mask, message)
        if masked != message:
            record.msg, record.args = masked, None
        return True


def setup(home: str, debug: bool = False) -> str:
    """Send every log to stderr and to `<home>/logs/otto.log`. Idempotent; returns the file's path.
    `debug` (a debug build) keeps DEBUG records, the HTTP transport's among them -- which host it
    connected to and where the TLS handshake failed."""
    if _state["path"]:
        return _state["path"]
    folder = os.path.join(home, "logs")
    os.makedirs(folder, exist_ok=True)
    path = os.path.join(folder, "otto.log")
    formatter = logging.Formatter(FORMAT)
    redact = Redact()
    root = logging.getLogger()
    for handler in (logging.StreamHandler(sys.stderr),
                    logging.handlers.RotatingFileHandler(path, maxBytes=MAX_BYTES, backupCount=BACKUPS,
                                                         encoding="utf-8")):
        handler.setFormatter(formatter)
        handler.addFilter(redact)
        root.addHandler(handler)
    root.setLevel(logging.DEBUG if debug else logging.INFO)
    for name in QUIET:
        logging.getLogger(name).setLevel(logging.INFO)
    if not debug:
        for name in ("httpx", "httpcore"):
            logging.getLogger(name).setLevel(logging.WARNING)
    logging.captureWarnings(True)

    log = logging.getLogger("otto_app")
    previous = sys.excepthook

    def excepthook(kind, value, tb):
        log.critical("uncaught %s", kind.__name__, exc_info=(kind, value, tb))
        previous(kind, value, tb)

    def thread_hook(args):
        log.critical("uncaught %s in %s", args.exc_type.__name__, getattr(args.thread, "name", "a thread"),
                     exc_info=(args.exc_type, args.exc_value, args.exc_traceback))

    sys.excepthook = excepthook
    threading.excepthook = thread_hook
    _state["path"] = path
    log.info("logging to %s (debug %s)", path, "on" if debug else "off")
    return path
