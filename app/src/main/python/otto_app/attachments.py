"""Files a person attaches to a message: PDF, DOCX, plain text, JSON and images.

otto's turn takes text only (agent/embed.py SessionHandle.run), so an attachment reaches the model as
text inside the message: a document's text, extracted here, or an image's description from otto's
vision model (the route phone_look uses). Kotlin (attach/AttachmentText.kt) wraps each in an
`<attached-file>` block marked as the file's content, not the person's instructions, and shows the
block as a chip.

Kotlin (attach/AttachmentReader.kt) calls `read` once per file, off the main thread, with a path in
the app's cache. It returns JSON: {"ok": true, ...} or {"ok": false, "error": {"code", "message"}}.
"""
from __future__ import annotations

import json
import logging
import re
import time
import zipfile
from pathlib import Path
from xml.etree import ElementTree

log = logging.getLogger("otto_app.attachments")

#: A file larger than this is not read at all.
MAX_FILE_BYTES = 20 * 1024 * 1024
#: The longest text one file contributes; the rest is cut and the block says so.
MAX_FILE_CHARS = 40_000
#: The longest text all of one message's files contribute together.
MAX_MESSAGE_CHARS = 80_000
MAX_PDF_PAGES = 300
#: otto's own cap for an image sent to a vision model (agent/pipeline/tools.py MAX_IMAGE_BYTES).
MAX_IMAGE_BYTES = 8 * 1024 * 1024

DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
TEXT_SUFFIXES = {".txt", ".md", ".markdown", ".csv", ".tsv", ".log", ".json", ".jsonl", ".xml",
                 ".yaml", ".yml", ".html", ".htm", ".ini", ".toml"}
IMAGE_TYPES = {"image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp"}

IMAGE_QUESTION = (
    "A person attached this image to a message for an assistant that cannot see it. Describe it "
    "completely: transcribe all visible text verbatim (keep its layout for tables, receipts and "
    "forms), then describe what it shows -- objects, people, charts with their values, screens and "
    "their contents -- precisely enough to answer questions about it without seeing it."
)

_W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"


def _ok(**data) -> str:
    return json.dumps({"ok": True, **data})


def _err(message: str, code: str = "failed") -> str:
    return json.dumps({"ok": False, "error": {"code": code, "message": message}})


def kind_of(name: str, mime: str) -> str:
    """pdf, docx, text, json, image, or "" for a file this cannot read."""
    mime = (mime or "").lower().split(";")[0].strip()
    suffix = Path(name or "").suffix.lower()
    if mime == "application/pdf" or suffix == ".pdf":
        return "pdf"
    if mime == DOCX_MIME or suffix == ".docx":
        return "docx"
    if mime in IMAGE_TYPES or suffix in {".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp"}:
        return "image"
    if mime in {"application/json", "application/x-ndjson"} or suffix in {".json", ".jsonl"}:
        return "json"
    if mime.startswith("text/") or suffix in TEXT_SUFFIXES:
        return "text"
    return ""


def _decode(data: bytes) -> str:
    for encoding in ("utf-8-sig", "utf-16"):
        try:
            text = data.decode(encoding)
            if encoding == "utf-16" and not data.startswith((b"\xff\xfe", b"\xfe\xff")):
                continue
            return text
        except UnicodeDecodeError:
            continue
    return data.decode("latin-1")


def _json_text(data: bytes) -> str:
    """Pretty-printed when it parses (so nesting reads), as sent when it does not (JSON Lines, say)."""
    raw = _decode(data)
    try:
        return json.dumps(json.loads(raw), indent=2, ensure_ascii=False)
    except ValueError:
        return raw


def _pdf_text(path: Path) -> tuple[str, dict]:
    from pypdf import PdfReader

    reader = PdfReader(str(path))
    if reader.is_encrypted:
        try:
            if not reader.decrypt(""):
                raise PermissionError
        except Exception as exc:
            raise PermissionError("the PDF is password-protected") from exc
    total = len(reader.pages)
    parts, chars = [], 0
    for number, page in enumerate(reader.pages[:MAX_PDF_PAGES], start=1):
        text = (page.extract_text() or "").strip()
        if text:
            parts.append(f"[page {number}]\n{text}")
            chars += len(text)
        if chars > MAX_FILE_CHARS:
            break
    meta = {"pages": total}
    if not parts:
        meta["note"] = "no text layer (a scanned PDF): attach the pages as images to have them read"
    return "\n\n".join(parts), meta


def _docx_text(path: Path) -> tuple[str, dict]:
    """Paragraphs and table rows from word/document.xml, in order; no python-docx (it needs lxml)."""
    with zipfile.ZipFile(path) as archive:
        try:
            xml = archive.read("word/document.xml")
        except KeyError as exc:
            raise ValueError("not a Word document (no word/document.xml)") from exc
    body = ElementTree.fromstring(xml).find(f"{_W}body")
    lines: list[str] = []

    def paragraph(p) -> str:
        out = []
        for node in p.iter():
            if node.tag == f"{_W}t" and node.text:
                out.append(node.text)
            elif node.tag == f"{_W}tab":
                out.append("\t")
            elif node.tag in (f"{_W}br", f"{_W}cr"):
                out.append("\n")
        return "".join(out)

    for block in (body if body is not None else []):
        if block.tag == f"{_W}p":
            lines.append(paragraph(block))
        elif block.tag == f"{_W}tbl":
            for row in block.iter(f"{_W}tr"):
                cells = [" ".join(paragraph(p) for p in cell.iter(f"{_W}p")).strip() for cell in row.iter(f"{_W}tc")]
                lines.append(" | ".join(cells))
    text = re.sub(r"\n{3,}", "\n\n", "\n".join(lines)).strip()
    return text, {}


class NoVision(Exception):
    """No vision model could describe the image: otto is not running here, or none answered."""


#: Tried after otto's routed vision model (Gemini) when it cannot answer: both read images, and a
#: person often has one of these keys when Gemini's credit runs out (2026-09-17). Never a text-only
#: model -- otto's own rule (agent/router/mapping.py Task.VISION): it would describe an image it never saw.
VISION_FALLBACKS: tuple[tuple[str, str, dict], ...] = (
    ("anthropic", "claude-haiku-4-5-20251001", {"max_tokens": 4096, "temperature": 0.0}),
    ("openai", "gpt-5-mini", {}),
)
#: A provider out of credit, or refusing the key, is skipped for this long before it is tried again.
UNAVAILABLE_FOR_S = 3600.0
#: One retry: a vendor's own backoff on an exhausted account made a failure take 40 s.
VISION_RETRIES = 1
VISION_TIMEOUT_S = 90

_unavailable: dict[str, tuple[float, str]] = {}

_CREDIT = re.compile(r"credit|quota|RESOURCE_EXHAUSTED|insufficient_quota|billing", re.I)
_KEY = re.compile(r"not set|api[_ ]key|401|403|unauthori[sz]ed|permission|authentication", re.I)


def _reason(exc: BaseException) -> str:
    text = f"{type(exc).__name__}: {exc}"
    if _CREDIT.search(text):
        return "out of credit or quota"
    if _KEY.search(text):
        return "no usable key"
    if "429" in text or "rate" in text.lower():
        return "rate limited"
    return text[:80]


def _lasting(reason: str) -> bool:
    return reason in ("out of credit or quota", "no usable key")


def _vision_candidates():
    """(provider, a function building its chat model), otto's routed choice first."""
    from agent.router.llm_provider import get_provider

    def routed():
        from agent.pipeline import tools as pt
        from agent.router.mapping import Task

        decision = pt._get_router().resolve(Task.VISION)
        params = {k: v for k, v in dict(decision.params).items() if k in ("temperature", "max_tokens")}
        return get_provider(decision.provider).chat_model(
            decision.model.id, max_retries=VISION_RETRIES, timeout=VISION_TIMEOUT_S, **params)

    yield "gemini", routed
    for provider, model, params in VISION_FALLBACKS:
        yield provider, (lambda p=provider, m=model, kw=params: get_provider(p).chat_model(
            m, max_retries=VISION_RETRIES, timeout=VISION_TIMEOUT_S, **kw))


def _describe(data: bytes, media_type: str) -> tuple[str, str]:
    """The image's description, and which provider gave it."""
    import base64

    from otto_app import bootstrap

    if not bootstrap.is_configured():
        raise NoVision("images are described by otto's vision model, which is only here when Otto runs on this phone")
    from agent.pipeline.vision import describe_image, sniff_media_type

    real = sniff_media_type(data) or media_type
    encoded = base64.b64encode(data).decode()
    reasons = []
    for provider, make in _vision_candidates():
        until, why = _unavailable.get(provider, (0.0, ""))
        if until > time.monotonic():
            reasons.append(f"{provider} {why}")
            continue
        try:
            text = describe_image(make(), encoded, real, IMAGE_QUESTION)
        except Exception as exc:
            why = _reason(exc)
            log.warning("vision via %s failed: %s", provider, why)
            if _lasting(why):
                _unavailable[provider] = (time.monotonic() + UNAVAILABLE_FOR_S, why)
            reasons.append(f"{provider} {why}")
            continue
        _unavailable.pop(provider, None)
        return text, provider
    raise NoVision("no vision model could read it (" + "; ".join(reasons) + ")")


def read(path: str, name: str = "", mime: str = "") -> str:
    """The text a file contributes to a message, with what was cut."""
    started = time.monotonic()
    file = Path(path)
    shown = name or file.name
    kind = kind_of(shown, mime)
    if not kind:
        return _err(f"{shown}: Otto reads PDF, Word (.docx), text, JSON and image files", code="unsupported")
    try:
        size = file.stat().st_size
    except OSError:
        return _err(f"{shown} could not be opened", code="missing")
    limit = MAX_IMAGE_BYTES if kind == "image" else MAX_FILE_BYTES
    if size > limit:
        return _err(f"{shown} is {size // (1024 * 1024)} MB; the limit is {limit // (1024 * 1024)} MB", code="too_large")
    meta: dict = {}
    try:
        if kind == "pdf":
            text, meta = _pdf_text(file)
        elif kind == "docx":
            text, meta = _docx_text(file)
        elif kind == "json":
            text = _json_text(file.read_bytes())
        elif kind == "text":
            text = _decode(file.read_bytes())
        else:
            text, provider = _describe(file.read_bytes(), mime or "image/jpeg")
            meta = {"note": f"described by {provider}"}
    except PermissionError as exc:
        return _err(f"{shown}: {exc}", code="protected")
    except NoVision as exc:
        return _err(f"{shown}: {exc}", code="no_vision")
    except (zipfile.BadZipFile, ElementTree.ParseError, ValueError) as exc:
        return _err(f"{shown} could not be read: {exc}", code="unreadable")
    except Exception as exc:  # a vendor failure, a broken PDF
        log.warning("reading a %s file failed", kind, exc_info=True)
        return _err(f"{shown} could not be read: {type(exc).__name__}: {exc}", code="failed")
    text = text.replace("\x00", "")
    truncated = len(text) > MAX_FILE_CHARS
    if truncated:
        text = text[:MAX_FILE_CHARS]
    # The kind and the sizes only: a file's name can itself be personal ("..._CV.pdf").
    log.info("read a %s file (%d bytes) -> %d chars%s [%.0f ms]", kind, size, len(text),
             ", cut" if truncated else "", (time.monotonic() - started) * 1000)
    return _ok(name=shown, kind=kind, size=size, text=text, chars=len(text), truncated=truncated, **meta)
