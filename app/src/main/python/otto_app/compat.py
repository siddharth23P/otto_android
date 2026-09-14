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

MIN_OTTO = (0, 2, 0)
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


def has(feature: str) -> bool:
    """Feature detection over the two surfaces, by name."""
    try:
        from agent import embed
        from agent.phone import tools as phone_tools
    except ImportError:
        return False
    if feature == "disabled_tools":
        return "disabled_tools" in inspect.signature(embed.SessionHandle.run).parameters
    if feature == "guidance":
        return "guidance" in inspect.signature(embed.SessionHandle.run).parameters
    if feature == "environ_keys":
        return "environ" in inspect.signature(embed.configure).parameters
    if feature == "transcript":
        return hasattr(embed.Runtime, "transcript")
    if feature == "phone_commit":
        return any(t.name == "phone_commit" for t in phone_tools.phone_tools(_Nothing()))
    return False


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
    features = tuple(f for f in ("disabled_tools", "guidance", "environ_keys", "transcript", "phone_commit") if has(f))
    return Compat(otto_version=version, api_version=api, features=features)
