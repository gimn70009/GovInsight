"""The same grouped list survives HWP/HWPX/DOCX/PDF without native readers."""
import struct
import zipfile
from pathlib import Path

import pytest

from app.domains.analysis.proposals.submission_requirements import collect_submission_requirements
from app.domains.monitoring.parsers.docx_parser import DocxParser
from app.domains.monitoring.parsers.hwp_parser import (
    HWPTAG_CTRL_HEADER,
    HWPTAG_LIST_HEADER,
    HWPTAG_TABLE,
    HwpParser,
)
from app.domains.monitoring.parsers.hwpx_parser import HwpxParser
from app.domains.monitoring.parsers.pdf_parser import PdfParser, _submission_layout_tables
from tests.domains.analysis.proposals.test_submission_requirements import request
from tests.domains.monitoring.test_hwp_parser import (
    FakeOleDocument,
    _paragraph_record,
    _record,
    _use_fake_document,
)

FIRST = "지원신청서 원본 1부"
SECOND = "사업계획서 사본 3부"


@pytest.mark.parametrize("kind", ["hwpx", "docx", "hwp", "pdf"])
def test_format_to_validated_submission_rows(kind, tmp_path, monkeypatch):
    paragraphs = [f"- {FIRST}", f"- {SECOND}"]
    if kind in {"hwpx", "docx"}:
        xml = ("<document><p><t>□ 신청서류</t></p><tbl>"
               "<tr><tc><p><t>구분</t></p></tc><tc><p><t>제출서류</t></p></tc></tr>"
               "<tr><tc><p><t>공통</t></p></tc><tc>"
               + "".join(f"<p><t>{p}</t></p>" for p in paragraphs)
               + "</tc></tr></tbl></document>")
        path = tmp_path / f"notice.{kind}"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("Contents/section0.xml" if kind == "hwpx" else "word/document.xml",
                             xml.encode())
        text = (HwpxParser() if kind == "hwpx" else DocxParser()).parse(path).text
    elif kind == "hwp":
        records = [_paragraph_record("□ 신청서류"), _record(HWPTAG_CTRL_HEADER, b" lbt", 1),
                   _record(HWPTAG_TABLE, struct.pack("<IHH", 0, 2, 2), 2)]
        for column, row, values in [(0, 0, ["구분"]), (1, 0, ["제출서류"]),
                                    (0, 1, ["공통"]), (1, 1, paragraphs)]:
            header = struct.pack("<HHI4H", len(values), 0, 0, column, row, 1, 1) + bytes(18)
            records.append(_record(HWPTAG_LIST_HEADER, header, 2))
            records.extend(_record(67, p.encode("utf-16le"), 4) for p in values)
        _use_fake_document(monkeypatch, FakeOleDocument(0, {"Section0": b"".join(records)}))
        text = HwpParser().parse(Path("notice.hwp")).text
    else:
        layout = f"□ 신청서류\n구분        제출서류\n공통        - {FIRST}\n          - {SECOND}\n"
        class Page:
            def extract_text(self, **kwargs):
                return layout if kwargs else layout.replace("        ", " ")
        text = PdfParser()._extract_page_text(Page())
    assert " ⏎ " in text
    rows = collect_submission_requirements(request(text, name=f"notice.{kind}"))
    assert [row.title for row in rows] == ["지원신청서", "사업계획서"]
    assert all(row.level == "MANDATORY" and row.stage == "APPLICATION" for row in rows)
    assert all(row.source.excerpt in text for row in rows)


def test_ambiguous_pdf_row_label_does_not_create_a_partial_table():
    layout = "구분        제출서류\n공통        - 지원신청서 1부\n모호한 값   다음 조건\n"
    result = _submission_layout_tables(layout)
    assert "[표]" not in result
    assert "모호한 값" in result and "다음 조건" in result
