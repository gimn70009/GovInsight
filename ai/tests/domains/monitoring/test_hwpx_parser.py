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
