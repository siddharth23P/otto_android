"""Attached files become text (otto_app/attachments.py): PDF, DOCX, text, JSON, images."""
from __future__ import annotations

import io
import json
import zipfile

import pytest

from otto_app import attachments


def pdf_bytes(pages: list[str]) -> bytes:
    """A small, valid PDF with one line of Helvetica text per page."""
    objects: list[bytes] = []
    kids = " ".join(f"{3 + 2 * i} 0 R" for i in range(len(pages)))
    objects.append(b"<< /Type /Catalog /Pages 2 0 R >>")
    objects.append(f"<< /Type /Pages /Kids [{kids}] /Count {len(pages)} >>".encode())
    font = 3 + 2 * len(pages)
    for i, text in enumerate(pages):
        content = f"BT /F1 12 Tf 72 720 Td ({text}) Tj ET".encode()
        objects.append(f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
                       f"/Resources << /Font << /F1 {font} 0 R >> >> /Contents {4 + 2 * i} 0 R >>".encode())
        objects.append(b"<< /Length %d >>\nstream\n" % len(content) + content + b"\nendstream")
    objects.append(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    out, offsets = io.BytesIO(), []
    out.write(b"%PDF-1.4\n")
    for number, body in enumerate(objects, start=1):
        offsets.append(out.tell())
        out.write(f"{number} 0 obj\n".encode() + body + b"\nendobj\n")
    xref = out.tell()
    out.write(f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode())
    for offset in offsets:
        out.write(f"{offset:010d} 00000 n \n".encode())
    out.write(f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode())
    return out.getvalue()


def docx_bytes() -> bytes:
    w = 'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"'
    xml = (f'<?xml version="1.0"?><w:document {w}><w:body>'
           '<w:p><w:r><w:t>Quarterly report</w:t></w:r></w:p>'
           '<w:p><w:r><w:t xml:space="preserve">Revenue </w:t></w:r><w:r><w:t>grew</w:t></w:r></w:p>'
           '<w:tbl><w:tr><w:tc><w:p><w:r><w:t>Q1</w:t></w:r></w:p></w:tc>'
           '<w:tc><w:p><w:r><w:t>12</w:t></w:r></w:p></w:tc></w:tr></w:tbl>'
           '</w:body></w:document>')
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("[Content_Types].xml", "<Types/>")
        z.writestr("word/document.xml", xml)
    return buf.getvalue()


def read(tmp_path, name: str, data: bytes, mime: str = "") -> dict:
    path = tmp_path / name
    path.write_bytes(data)
    return json.loads(attachments.read(str(path), name, mime))


def test_a_pdf_gives_its_text_page_by_page(tmp_path):
    got = read(tmp_path, "notes.pdf", pdf_bytes(["Hello from page one", "And page two"]), "application/pdf")
    assert got["ok"] and got["kind"] == "pdf" and got["pages"] == 2
    assert "[page 1]\nHello from page one" in got["text"] and "[page 2]\nAnd page two" in got["text"]


def test_a_pdf_with_no_text_says_to_attach_images(tmp_path):
    got = read(tmp_path, "scan.pdf", pdf_bytes([""]))
    assert got["ok"] and got["text"] == "" and "scanned" in got["note"]


def test_a_word_document_keeps_paragraphs_and_tables(tmp_path):
    got = read(tmp_path, "report.docx", docx_bytes())
    assert got["ok"] and got["kind"] == "docx"
    assert got["text"] == "Quarterly report\nRevenue grew\nQ1 | 12"


def test_text_and_json(tmp_path):
    got = read(tmp_path, "list.txt", "milk\neggs\n".encode("utf-8-sig"), "text/plain")
    assert got["ok"] and got["text"] == "milk\neggs\n"
    got = read(tmp_path, "data.json", b'{"a":[1,2]}', "application/json")
    assert got["kind"] == "json" and got["text"] == '{\n  "a": [\n    1,\n    2\n  ]\n}'
    got = read(tmp_path, "rows.jsonl", b'{"a":1}\n{"a":2}\n')
    assert got["ok"] and got["text"] == '{"a":1}\n{"a":2}\n'
    assert read(tmp_path, "u16.txt", "héllo".encode("utf-16"))["text"] == "héllo"


def test_long_text_is_cut_and_says_so(tmp_path, monkeypatch):
    monkeypatch.setattr(attachments, "MAX_FILE_CHARS", 10)
    got = read(tmp_path, "long.txt", b"x" * 50)
    assert got["truncated"] and got["chars"] == 10


def test_what_cannot_be_read_says_why(tmp_path, monkeypatch):
    assert read(tmp_path, "a.zip", b"PK")["error"]["code"] == "unsupported"
    assert read(tmp_path, "bad.docx", b"not a zip")["error"]["code"] == "unreadable"
    monkeypatch.setattr(attachments, "MAX_FILE_BYTES", 4)
    assert read(tmp_path, "big.txt", b"12345")["error"]["code"] == "too_large"
    missing = json.loads(attachments.read(str(tmp_path / "gone.txt"), "gone.txt", "text/plain"))
    assert missing["error"]["code"] == "missing"


def test_an_image_is_described_by_ottos_vision_model(tmp_path, monkeypatch, bridge):
    import json as _json

    from otto_app import bootstrap

    png = b"\x89PNG\r\n\x1a\n" + b"\x00" * 16
    monkeypatch.setitem(bootstrap._state, "configured", False)
    assert read(tmp_path, "shot.png", png)["error"]["code"] == "no_vision"

    bootstrap.configure(str(tmp_path / "otto"), _json.dumps({"INCEPTION_API_KEY": "x"}))
    seen = {}

    def fake_vision(question, data, media_type):
        seen.update(question=question, size=len(data), media_type=media_type)
        return "A receipt: MILK 2.50"

    import agent.phone.tools as phone_tools
    monkeypatch.setattr(phone_tools, "_default_vision", fake_vision)
    got = read(tmp_path, "shot.jpg", png, "image/jpeg")
    assert got["ok"] and got["kind"] == "image" and got["text"] == "A receipt: MILK 2.50"
    # The real type comes from the bytes, not the name.
    assert seen["media_type"] == "image/png" and "transcribe" in seen["question"]


@pytest.mark.parametrize("name,mime,kind", [
    ("a.PDF", "", "pdf"), ("x", "application/pdf", "pdf"),
    ("r.docx", "", "docx"), ("p.jpeg", "", "image"), ("p", "image/webp", "image"),
    ("n.md", "", "text"), ("n", "text/csv; charset=utf-8", "text"), ("d.json", "", "json"),
    ("s.exe", "application/octet-stream", ""), ("old.doc", "application/msword", ""),
])
def test_kinds(name, mime, kind):
    assert attachments.kind_of(name, mime) == kind
