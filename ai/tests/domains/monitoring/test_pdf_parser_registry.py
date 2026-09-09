import io
import zipfile
from pathlib import Path

from app.domains.monitoring.parsers.registry import AttachmentParserRegistry


def hwpx_bytes(text: str) -> bytes:
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as archive:
        archive.writestr("Contents/section0.xml", f"<root><p><t>{text}</t></p></root>")
    return stream.getvalue()


def test_registry_reads_hwpx_content_with_hwp_or_pdf_extension(tmp_path: Path) -> None:
    registry = AttachmentParserRegistry()
    for name in ["양식.HWP", "양식.PDF", "양식.hwpx"]:
        path = tmp_path / name
        path.write_bytes(hwpx_bytes("실제 본문"))
        parser = registry.find(name)
        assert parser is not None
        assert parser.parse(path).text == "실제 본문"
    assert registry.find("양식.docx") is None
    assert registry.find("표.xlsx") is None


def test_zip_recovers_mislabeled_hwpx_and_prefers_correct_extension(tmp_path: Path) -> None:
    path = tmp_path / "양식.ZIP"
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("신청서.hwp", hwpx_bytes("사업 목표"))
        archive.writestr("신청서.hwpx", hwpx_bytes("사업 목표"))
        archive.writestr("서약서.hwp", hwpx_bytes("서약 내용"))
        archive.writestr("신청서.docx", "미지원")
    result = AttachmentParserRegistry().find(path.name).parse(path)
    assert result.text == "[파일: 신청서.hwpx]\n사업 목표\n\n[파일: 서약서.hwp]\n서약 내용"
    assert [e["status"] for e in result.archive_entries] == [
        "DUPLICATE",
        "COMPLETED",
        "COMPLETED",
        "UNSUPPORTED",
    ]
    assert [e["actualFormat"] for e in result.archive_entries] == ["HWPX", "HWPX", "HWPX", None]
