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

from otto_app import fake, logs

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


def configure(home: str, keys_json: str = "{}", debug: bool = False, fake_model: bool = False) -> str:
    """Point otto at `home` and inject `keys` (a JSON object of env var ->
    value). Idempotent. Returns a JSON status the Kotlin side shows. Logging
    starts first, so even a build that cannot import otto says why.
    `fake_model` (debug builds, for the emulator smoke test) swaps otto's
    pipeline for otto_app/fake.py's script."""
    logs.setup(home, bool(debug))
    if not available():
        return json.dumps({"ok": False, "available": False, "error": _state["error"]})
    keys = json.loads(keys_json or "{}")
    if fake_model:
        os.environ[fake.ENV] = "1"
        keys.setdefault("INCEPTION_API_KEY", fake.PLACEHOLDER_KEY)
    present = sorted(name for name, value in keys.items() if value)
    log.info("configuring otto at %s; keys given: %s", home, ", ".join(present) or "none")
    trust_store()
    from agent import embed

    try:
        embed.configure(home, environ={k: v for k, v in keys.items() if v})
    except RuntimeError as exc:
        log.exception("otto would not configure")
        return json.dumps({"ok": False, "available": True, "error": str(exc)})
    _state.update(configured=True, home=home)
    if fake_model:
        fake.install()
    os.environ.setdefault("OTTO_NO_ANIMATION", "1")
    from otto_app import compat

    otto, api = compat.installed_version(), int(getattr(embed, "API_VERSION", 0))
    log.info("otto %s (embedding api %s) ready on Python %s", otto, api, sys.version.split()[0])
    return json.dumps({"ok": True, "available": True, "home": home, "otto": otto, "api": api})


def trust_store() -> str:
    """Point TLS at certifi's CA bundle unless something already chose one; returns the bundle used.

    The vendor SDKs' HTTP client (httpx2) trusts the system store through `truststore`, which has no
    Android backend, so Python fell back to OpenSSL's default CA path -- empty on Android -- and every
    Anthropic and OpenAI call failed with CERTIFICATE_VERIFY_FAILED, shown as "Connection error."
    (2026-09-16). httpx2, requests and urllib3 all honour SSL_CERT_FILE."""
    chosen = os.environ.get("SSL_CERT_FILE", "")
    if chosen and os.path.isfile(chosen):
        return chosen
    try:
        import certifi

        bundle = certifi.where()
    except Exception:
        log.error("no CA bundle: certifi is missing, so TLS connections will fail", exc_info=True)
        return ""
    if not os.path.isfile(bundle):
        log.error("certifi's CA bundle is not a file (%s), so TLS connections will fail", bundle)
        return ""
    os.environ["SSL_CERT_FILE"] = bundle
    log.info("TLS trusts %s", bundle)
    return bundle


def is_configured() -> bool:
    return bool(_state["configured"])
