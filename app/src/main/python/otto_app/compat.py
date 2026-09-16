"""Which otto this app can drive, and which of its features exist.

The app pins one otto release (requirements.txt) and Dependabot bumps it.
Between the bump and a person looking, this module is the check: too old
and the surfaces are missing; an API_VERSION above MAX_API means the
contract changed in a way this app was never tested against, and that is a
loud refusal, not a guess. Everything optional is feature-detected with
`has()` and degrades.
"""
from __future__ import annotations

import inspect
from dataclasses import dataclass
from importlib import metadata

MIN_OTTO = (0, 1, 2)
MAX_API = 1


class IncompatibleOtto(Exception):
    """The installed otto cannot be driven by this app; the message says why."""


@dataclass(frozen=True)
class Compat:
    otto_version: str
    api_version: int
    features: tuple[str, ...]


def _version_tuple(text: str) -> tuple[int, ...]:
    """Leading numeric components only: "0.3.0rc1" is (0, 3, 0)."""
    parts = []
    for piece in text.split("."):
        digits = ""
        for ch in piece:
            if not ch.isdigit():
                break
            digits += ch
        if not digits:
            break
        parts.append(int(digits))
        if digits != piece:
            break
    return tuple(parts)


def installed_version() -> str:
    try:
        return metadata.version("otto-cli-agent")
    except metadata.PackageNotFoundError:
        return "0.0.0"


#: The operations `otto serve` answers beyond the first embedding API, by
#: the agent/embed.py names each one needs. entry.py leaves out a function
#: whose feature is missing, which Kotlin reads as "needs a newer otto".
SERVE_FEATURES: dict[str, tuple[str, ...]] = {
    "rename": ("Runtime.rename_session", "SessionHandle.rename"),
    "export": ("Runtime.export_session", "Runtime.import_session"),
    "usage": ("SessionHandle.usage_report",),
    "setup_status": ("setup_status",),
    "probe": ("probe",),
    "doctor_report": ("doctor_report",),
    "models": ("models",),
    "routing": ("routing", "routing_options", "set_pin", "clear_pin"),
    "lessons": ("lessons", "delete_lesson", "clear_lessons"),
    "notes": ("notes", "note", "delete_note"),
    "files": ("Runtime.document_file", "FileTooLarge"),
}


def _embed_has(embed, dotted: str) -> bool:
    target = embed
    for part in dotted.split("."):
        target = getattr(target, part, None)
        if target is None:
            return False
    return True


def has(feature: str) -> bool:
    """Feature detection over the two surfaces, by name."""
    try:
        from agent import embed
    except ImportError:
        return False
    run_params = inspect.signature(embed.SessionHandle.run).parameters
    if feature == "disabled_tools":
        return "disabled_tools" in run_params
    if feature == "guidance":
        return "guidance" in run_params
    if feature == "phone_decision":
        return "phone" in run_params
    if feature == "environ_keys":
        return "environ" in inspect.signature(embed.configure).parameters
    if feature == "transcript":
        return hasattr(embed.Runtime, "transcript")
    if feature == "phone_commit":
        try:
            from agent.phone import tools as phone_tools
        except ImportError:
            return False
        return any(t.name == "phone_commit" for t in phone_tools.phone_tools(_Nothing()))
    if feature in SERVE_FEATURES:
        return all(_embed_has(embed, name) for name in SERVE_FEATURES[feature])
    return False


FEATURES: tuple[str, ...] = ("disabled_tools", "guidance", "environ_keys", "transcript", "phone_commit",
                             "phone_decision", *SERVE_FEATURES)


class _Nothing:
    """A backend that is never called: phone_tools() only needs an object."""


def probe() -> Compat:
    version = installed_version()
    if _version_tuple(version) < MIN_OTTO:
        raise IncompatibleOtto(
            f"otto {version} is older than {'.'.join(map(str, MIN_OTTO))}, the first release with the "
            "embedding API; update otto"
        )
    from agent import embed

    api = int(getattr(embed, "API_VERSION", 0))
    if api > MAX_API:
        raise IncompatibleOtto(
            f"otto {version} speaks embedding API {api}; this app understands up to {MAX_API} -- update the app"
        )
    features = tuple(f for f in FEATURES if has(f))
    return Compat(otto_version=version, api_version=api, features=features)
