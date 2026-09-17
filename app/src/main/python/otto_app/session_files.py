"""The files a session was given, kept where otto can read them again.

Each attached file is recorded in the session's workspace (agent/embed.py `session_workspace`):

    <otto home>/workspaces/<session>/attachments/
        index.json                one entry per file, newest last
        <id>-<name>.txt           the text otto read: a document's text, an image's description
        originals/<id>-<name>     a copy of the original -- only for a file shared into Otto, whose
                                  read permission ends with the screen that received it

A file picked with the paperclip is not copied: the app keeps a lasting read permission to it
(ContentResolver.takePersistableUriPermission) and the entry holds its URI. The text file is what a
later off-phone turn reads with read_file, after the conversation has been summarised past it; the
message that carried the file names it.

Kotlin (attach/SessionFiles.kt) calls these; every function returns JSON, {"ok": ...}.
"""
from __future__ import annotations

import json
import logging
import re
import shutil
import threading
import time
from pathlib import Path

log = logging.getLogger("otto_app.session_files")

DIR = "attachments"
INDEX = "index.json"
ORIGINALS = "originals"
DOCUMENTS = "otto_research"

_SESSION = re.compile(r"^[0-9a-f]{32}$")
_ID = re.compile(r"^[A-Za-z0-9-]{8,64}$")
_UNSAFE = re.compile(r"[^A-Za-z0-9._-]+")
_lock = threading.Lock()


def _ok(**data) -> str:
    return json.dumps({"ok": True, **data})


def _err(message: str, code: str = "failed") -> str:
    return json.dumps({"ok": False, "error": {"code": code, "message": message}})


def _workspace(session_id: str) -> Path:
    if not _SESSION.fullmatch(session_id or ""):
        raise ValueError(f"{str(session_id)[:40]!r} is not a session id")
    from agent import embed

    return Path(embed.session_workspace(session_id))


def _folder(session_id: str) -> Path:
    return _workspace(session_id) / DIR


def _read_index(folder: Path) -> list[dict]:
    try:
        entries = json.loads((folder / INDEX).read_text(encoding="utf-8"))
        return [e for e in entries if isinstance(e, dict)]
    except (OSError, ValueError):
        return []


def _write_index(folder: Path, entries: list[dict]) -> None:
    folder.mkdir(parents=True, exist_ok=True)
    temp = folder / (INDEX + ".tmp")
    temp.write_text(json.dumps(entries, ensure_ascii=False, indent=1), encoding="utf-8")
    temp.replace(folder / INDEX)


def _safe(name: str) -> str:
    return (_UNSAFE.sub("_", name).strip("._") or "file")[-80:]


def store(session_id: str, meta_json: str, text: str, original_path: str = "") -> str:
    """Record one file: its text, its entry, and a copy of the original when `original_path` names
    one (a shared file). `meta` carries id, name, kind, size, mime, uri, truncated, pages, note."""
    try:
        meta = json.loads(meta_json or "{}")
        file_id = str(meta.get("id", ""))
        if not _ID.fullmatch(file_id):
            return _err("a file needs an id", code="invalid")
        folder = _folder(session_id)
        name = str(meta.get("name") or "file")[:200]
        with _lock:
            folder.mkdir(parents=True, exist_ok=True)
            text_name = f"{file_id}-{_safe(name)}.txt"
            (folder / text_name).write_text(text or "", encoding="utf-8")
            copy = ""
            if original_path:
                source = Path(original_path)
                if source.is_file():
                    (folder / ORIGINALS).mkdir(exist_ok=True)
                    copy = f"{ORIGINALS}/{file_id}-{_safe(name)}"
                    shutil.move(str(source), folder / copy)
            entry = {
                "id": file_id, "name": name, "kind": str(meta.get("kind", "text")),
                "size": int(meta.get("size") or 0), "mime": str(meta.get("mime") or ""),
                "uri": str(meta.get("uri") or ""), "copy": copy, "text": text_name,
                "chars": len(text or ""), "truncated": bool(meta.get("truncated")),
                "pages": meta.get("pages"), "note": str(meta.get("note") or ""),
                "added_at": int(time.time() * 1000),
            }
            entries = [e for e in _read_index(folder) if e.get("id") != file_id] + [entry]
            _write_index(folder, entries)
    except ValueError as exc:
        return _err(str(exc), code="invalid")
    except OSError as exc:
        log.warning("storing a %s file failed", meta.get("kind", "?"), exc_info=True)
        return _err(f"the file could not be saved: {exc.strerror or exc}")
    log.info("stored a %s file (%d chars%s)", entry["kind"], entry["chars"], ", copied" if copy else "")
    return _ok(entry=entry, path=f"{DIR}/{text_name}")


def listing(session_id: str) -> str:
    """The session's attached files (with where their originals are), and otto's own documents."""
    try:
        workspace = _workspace(session_id)
    except ValueError as exc:
        return _err(str(exc), code="invalid")
    folder = workspace / DIR
    files = []
    for entry in _read_index(folder):
        entry = dict(entry)
        entry["copy_path"] = str(folder / entry["copy"]) if entry.get("copy") else ""
        entry["readable"] = (folder / str(entry.get("text", ""))).is_file()
        files.append(entry)
    documents = []
    research = workspace / DOCUMENTS
    if research.is_dir():
        for doc in sorted(research.glob("*/document.md"), key=lambda p: p.stat().st_mtime):
            documents.append({"name": doc.parent.name, "path": doc.relative_to(workspace).as_posix(),
                              "size": doc.stat().st_size, "modified_at": int(doc.stat().st_mtime * 1000)})
    return _ok(files=files, documents=documents)


def text(session_id: str, file_id: str) -> str:
    """The text otto has for one file."""
    try:
        folder = _folder(session_id)
    except ValueError as exc:
        return _err(str(exc), code="invalid")
    entry = next((e for e in _read_index(folder) if e.get("id") == file_id), None)
    if entry is None:
        return _err("no such file in this session", code="missing")
    try:
        body = (folder / str(entry.get("text", ""))).read_text(encoding="utf-8")
    except OSError:
        return _err("the file's text is gone", code="missing")
    return _ok(name=entry.get("name", ""), kind=entry.get("kind", ""), text=body)


def remove(session_id: str, file_id: str) -> str:
    """Forget one file: its text and any copy. The entry comes back, so the app can release the
    read permission its URI holds."""
    try:
        folder = _folder(session_id)
    except ValueError as exc:
        return _err(str(exc), code="invalid")
    with _lock:
        entries = _read_index(folder)
        entry = next((e for e in entries if e.get("id") == file_id), None)
        if entry is None:
            return _err("no such file in this session", code="missing")
        for part in (entry.get("text"), entry.get("copy")):
            if part:
                (folder / str(part)).unlink(missing_ok=True)
        _write_index(folder, [e for e in entries if e.get("id") != file_id])
    return _ok(entry=entry)


def forget(session_id: str) -> str:
    """A session is being deleted: remove all its files, and return the URIs whose read
    permissions the app should release."""
    try:
        folder = _folder(session_id)
    except ValueError as exc:
        return _err(str(exc), code="invalid")
    with _lock:
        uris = [e["uri"] for e in _read_index(folder) if e.get("uri")]
        shutil.rmtree(folder, ignore_errors=True)
    return _ok(uris=uris)


def uris_in_use() -> str:
    """Every URI any session still refers to, so the app can release the rest."""
    from agent import embed

    root = Path(embed.session_workspace("0" * 32)).parent
    uris = set()
    if root.is_dir():
        for index in root.glob(f"*/{DIR}/{INDEX}"):
            uris.update(e["uri"] for e in _read_index(index.parent) if e.get("uri"))
    return _ok(uris=sorted(uris))
