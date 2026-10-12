import tempfile
import zipfile
from pathlib import Path

import pytest

from app.domains.monitoring.parsers.hwpx_parser import HwpxParseError, HwpxParser


def _write_hwpx(path: Path, sections: dict[str, str]) -> None:
    with zipfile.ZipFile(path, "w") as archive:
        for name, content in sections.items():
            archive.writestr(name, content)


def test_extracts_text_from_sections_in_number_order() -> None:
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "sample.hwpx"
        _write_hwpx(
            path,
            {
                "Contents/section10.xml": '<section><p><t>세 번째</t></p></section>',
                "Contents/section2.xml": '<section><p><t>두 번째</t></p></section>',
                "Contents/section0.xml": (
                    '<section><p><run><t>첫 번째 </t><t>문단</t></run></p></section>'
                ),
            },
        )

        result = HwpxParser().parse(path)

    assert result.text == "첫 번째 문단\n두 번째\n세 번째"


def test_rejects_invalid_zip() -> None:
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "broken.hwpx"
        path.write_bytes(b"not a zip file")

        with pytest.raises(HwpxParseError, match="읽을 수 없습니다"):
            HwpxParser().parse(path)


def test_rejects_hwpx_without_text() -> None:
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "empty.hwpx"
        _write_hwpx(path, {"Contents/section0.xml": "<section><p /></section>"})

        with pytest.raises(HwpxParseError, match="텍스트를 찾지 못했습니다"):
            HwpxParser().parse(path)


def test_nested_table_preserves_roles_and_choices_without_flattened_duplicate() -> None:
    xml = """
    <section xmlns:hp="urn:hwpx:paragraph"><hp:p><hp:run><hp:tbl>
      <hp:tr>
        <hp:tc><hp:p><hp:run><hp:t>주관기관유형</hp:t></hp:run></hp:p></hp:tc>
        <hp:tc><hp:p><hp:run><hp:t>□ 제한없음 ■ 기업</hp:t></hp:run></hp:p></hp:tc>
      </hp:tr>
      <hp:tr>
        <hp:tc><hp:p><hp:run><hp:t>주관기관 외 필수참여기관</hp:t></hp:run></hp:p></hp:tc>
        <hp:tc><hp:p><hp:run><hp:t>■ 제한없음 □ 기업</hp:t></hp:run></hp:p></hp:tc>
      </hp:tr>
    </hp:tbl></hp:run></hp:p></section>
    """
    assert HwpxParser()._parse_section(xml.encode()) == [
        "주관기관유형", "□ 제한없음 ■ 기업",
        "주관기관 외 필수참여기관", "■ 제한없음 □ 기업",
    ]


def test_text_before_and_after_nested_tables_keeps_document_order() -> None:
    xml = """
    <section><p><run><t>신청 자격</t><tbl><tr><tc><p><run>
      <t>주관기관: 기업</t><tbl><tr><tc><p><run><t>단, 참여제한 기관 제외</t>
      </run></p></tc></tr></tbl><t>공동기관: 대학</t>
    </run></p></tc></tr></tbl><t>접수 마감: 2026년 10월 31일</t></run></p></section>
    """
    assert HwpxParser()._parse_section(xml.encode()) == [
        "신청 자격", "주관기관: 기업", "단, 참여제한 기관 제외", "공동기관: 대학",
        "접수 마감: 2026년 10월 31일",
    ]


def test_repeated_cell_values_are_preserved_for_each_role() -> None:
    xml = """
    <section><p><run><tbl>
      <tr><tc><p><t>주관기관</t></p></tc><tc><p><t>기업</t></p></tc></tr>
      <tr><tc><p><t>공동기관</t></p></tc><tc><p><t>기업</t></p></tc></tr>
    </tbl></run></p></section>
    """
    assert HwpxParser()._parse_section(xml.encode()) == ["주관기관", "기업", "공동기관", "기업"]
