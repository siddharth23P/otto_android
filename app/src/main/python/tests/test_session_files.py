"""A session's attached files, kept where otto can read them again (otto_app/session_files.py)."""
from __future__ import annotations

import json

import pytest

from otto_app import bootstrap, session_files as sf

SID = "a" * 32


@pytest.fixture
def home(tmp_path, bridge):
    bootstrap.configure(str(tmp_path / "otto"), json.dumps({"INCEPTION_API_KEY": "x"}))
    from agent import embed

    return embed.session_workspace(SID)


def call(fn, *args):
    return json.loads(fn(*args))


def meta(**kw):
    base = {"id": "f1a2b3c4d5", "name": "My CV.pdf", "kind": "pdf", "size": 1234, "mime": "application/pdf",
            "uri": "content://com.android.providers.downloads.documents/document/42", "pages": 2}
    return json.dumps({**base, **kw})


def test_a_picked_file_keeps_its_text_and_a_reference_not_a_copy(home):
    got = call(sf.store, SID, meta(), "[page 1]\nSiddharth")
    assert got["ok"] and got["path"] == "attachments/f1a2b3c4d5-My_CV.pdf.txt"
    assert (home / got["path"]).read_text() == "[page 1]\nSiddharth"
    assert not (home / "attachments" / "originals").exists()
    files = call(sf.listing, SID)["files"]
    assert [(f["name"], f["uri"], f["copy"], f["readable"], f["pages"]) for f in files] == [
        ("My CV.pdf", "content://com.android.providers.downloads.documents/document/42", "", True, 2)]


def test_a_shared_file_is_moved_in_as_a_copy(home, tmp_path):
    cache = tmp_path / "cache-copy.pdf"
    cache.write_bytes(b"%PDF")
    got = call(sf.store, SID, meta(uri=""), "text", str(cache))
    entry = got["entry"]
    assert entry["copy"] == "originals/f1a2b3c4d5-My_CV.pdf" and not cache.exists()
    listed = call(sf.listing, SID)["files"][0]
    assert listed["copy_path"].endswith("attachments/originals/f1a2b3c4d5-My_CV.pdf")
    assert open(listed["copy_path"], "rb").read() == b"%PDF"


def test_text_remove_and_forget(home):
    call(sf.store, SID, meta(), "one")
    call(sf.store, SID, meta(id="f2a2b3c4d5", name="b.txt", kind="text", uri="content://x/2"), "two")
    assert call(sf.text, SID, "f2a2b3c4d5")["text"] == "two"
    removed = call(sf.remove, SID, "f1a2b3c4d5")
    assert removed["entry"]["uri"].endswith("/42")
    assert [f["id"] for f in call(sf.listing, SID)["files"]] == ["f2a2b3c4d5"]
    assert call(sf.text, SID, "f1a2b3c4d5")["error"]["code"] == "missing"
    assert call(sf.uris_in_use)["uris"] == ["content://x/2"]
    assert call(sf.forget, SID)["uris"] == ["content://x/2"]
    assert call(sf.listing, SID)["files"] == [] and not (home / "attachments").exists()


def test_storing_the_same_file_again_replaces_its_entry(home):
    call(sf.store, SID, meta(), "old")
    call(sf.store, SID, meta(), "new")
    files = call(sf.listing, SID)["files"]
    assert len(files) == 1 and call(sf.text, SID, "f1a2b3c4d5")["text"] == "new"


def test_research_documents_are_listed_too(home):
    doc = home / "otto_research" / "tides" / "document.md"
    doc.parent.mkdir(parents=True)
    doc.write_text("# Tides")
    documents = call(sf.listing, SID)["documents"]
    assert [(d["name"], d["path"]) for d in documents] == [("tides", "otto_research/tides/document.md")]


@pytest.mark.parametrize("session", ["", "../../etc", "A" * 32, "a" * 31])
def test_only_a_real_session_id_names_a_folder(home, session):
    assert call(sf.listing, session)["error"]["code"] == "invalid"
    assert call(sf.store, session, meta(), "x")["error"]["code"] == "invalid"


def test_a_file_id_must_be_plain(home):
    assert call(sf.store, SID, meta(id="../x"), "x")["error"]["code"] == "invalid"
