import struct
from pathlib import Path

import pytest

from app.domains.monitoring.parsers.hwp_parser import (
    HWPTAG_CTRL_HEADER,
    HWPTAG_LIST_HEADER,
    HWPTAG_TABLE,
    HwpParser,
    _extract_paragraphs,
)
from app.domains.monitoring.parsers.hwpx_parser import HwpxParser
from app.domains.monitoring.parsers.table_text import Cell, render_table
from tests.domains.monitoring.test_hwp_parser import (
    FakeOleDocument,
    _paragraph_record,
    _record,
    _use_fake_document,
)


def hwp_table():
    records = [
        _paragraph_record("신청서류"),
        _record(HWPTAG_CTRL_HEADER, b" lbt", 1),
        _record(HWPTAG_TABLE, struct.pack("<IHH", 0, 4, 3), 2),
    ]
    cells = [
        (0, 0, 1, 1, "구분"),
        (1, 0, 1, 1, "서류명"),
        (2, 0, 1, 1, "비고"),
        (0, 1, 1, 2, "필수"),
        (1, 1, 1, 1, "신청서"),
        (2, 1, 1, 1, "직인 날인"),
        (1, 2, 1, 1, "사업계획서"),
        (2, 2, 1, 1, ""),
        (0, 3, 1, 1, ""),
        (1, 3, 1, 1, "추천서"),
        (2, 3, 1, 1, ""),
    ]
    for column, row, width, height, text in cells:
        header = struct.pack("<HHI4H", 1, 0, 0, column, row, width, height) + bytes(18)
        records.append(_record(HWPTAG_LIST_HEADER, header, 2))
        records.append(_record(67, text.encode("utf-16le"), 4))
    records.append(_paragraph_record("다음 절"))
    return b"".join(records)


def test_hwp_record_levels_and_explicit_merges_preserve_submission_relationships(monkeypatch):
    document = FakeOleDocument(0, {"Section0": hwp_table()})
    _use_fake_document(monkeypatch, document)
    text = HwpParser().parse(Path("sample.hwp")).text
    assert text == (
        "신청서류\n[표]\n구분\t서류명\t비고\n필수\t신청서\t직인 날인\n"
        "필수\t사업계획서\t\n\t추천서\t\n[/표]\n다음 절"
    )


def test_invalid_hwp_cell_metadata_preserves_words_without_guessing_relations():
    data = hwp_table().replace(struct.pack("<4H", 0, 1, 1, 2), struct.pack("<4H", 0, 1, 1, 99))
    text = "\n".join(_extract_paragraphs(data))
    assert "[표]" not in text
    assert all(
        word in text for word in ("신청서류", "필수", "신청서", "사업계획서", "추천서", "다음 절")
    )


def test_hwpx_uses_addresses_not_xml_cell_order_and_only_explicit_row_merges():
    cells = [
        (0, 0, 1, "구분"),
        (0, 1, 1, "서류명"),
        (1, 1, 1, "신청서"),
        (1, 0, 2, "필수"),
        (2, 1, 1, "사업계획서"),
        (3, 0, 1, ""),
        (3, 1, 1, "추천서"),
    ]
    rows = []
    for row in range(4):
        items = []
        for r, c, span, text in cells:
            if r == row:
                items.append(
                    f'<tc><cellAddr rowAddr="{r}" colAddr="{c}"/>'
                    f'<cellSpan rowSpan="{span}" colSpan="1"/>'
                    f"<p><t>{text}</t></p></tc>"
                )
        rows.append("<tr>" + "".join(items) + "</tr>")
    xml = "<section><p><t>신청서류</t><tbl>" + "".join(rows) + "</tbl><t>끝</t></p></section>"
    assert HwpxParser()._parse_section(xml.encode()) == [
        "신청서류",
        "[표]\n구분\t서류명\n필수\t신청서\n필수\t사업계획서\n\t추천서\n[/표]",
        "끝",
    ]


@pytest.mark.parametrize(
    "cells",
    [
        [Cell(0, 0, "중복", 2, 2), Cell(1, 1, "겹침")],
        [Cell(0, 0, "내용"), Cell(100_000_000, 3, "내용")],
        [Cell(0, 0, "음수", -2), Cell(1, 1, "내용")],
    ],
)
def test_invalid_or_excessive_grids_do_not_invent_cells(cells):
    assert render_table(cells) is None


def test_official_startup_xml_matches_backend_submission_fixture():
    root = Path(__file__).resolve().parents[4]
    xml = (root / "test-fixtures/document-tables/startup-table.xml").read_bytes()
    parsed = HwpxParser()._parse_section(xml)
    text = (
        root / "backend/src/test/resources/submission-documents/startup-structured.txt"
    ).read_text(
        encoding="utf-8",
    )
    assert len(parsed) == 1 and parsed[0] in text
    assert "필수\t사업계획서\t운영·지원 사업계획서\t기관장 직인본 (HWP 또는 PDF)" in parsed[0]
    assert "해당시\t기업신용평가등급 확인서" in parsed[0]


def test_official_cbam_xml_matches_backend_submission_fixture():
    root = Path(__file__).resolve().parents[4]
    xml = (root / "test-fixtures/document-tables/cbam-table.xml").read_bytes()
    parsed = HwpxParser()._parse_section(xml)
    text = (
        root / "backend/src/test/resources/submission-documents/cbam-structured.txt"
    ).read_text(encoding="utf-8")
    assert len(parsed) == 1 and parsed[0] in text
    assert len(parsed[0].splitlines()) == 14  # Envelope, header, eleven documents.
    assert "제출서류 목록\t유형1\t유형2" in parsed[0]
    assert "EU 수출실적 증빙양식(해당 시)\t○\t○" in parsed[0]
    assert "표준재무제표증명원 각 1부(‘23, ’24, ‘25년)\t○\tX" in parsed[0]


def test_official_smart_award_organization_merges_match_backend_fixture():
    root = Path(__file__).resolve().parents[4]
    xml = (root / "test-fixtures/document-tables/smart-award-organizations.xml").read_bytes()
    text = (
        root / "backend/src/test/resources/organizations/smart-award-structured.txt"
    ).read_text(encoding="utf-8")
    parsed = HwpxParser()._parse_section(xml)
    assert len(parsed) == 1 and parsed[0] == text.strip("\n")
    row = parsed[0].splitlines()[1].split("\t")
    assert row == ["주관기관"] * 5 + [""] + ["중소벤처기업부"] * 5
